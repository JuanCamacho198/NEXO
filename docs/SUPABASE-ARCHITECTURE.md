# NextPage — Arquitectura con Supabase

> Documento de arquitectura y roadmap para la integración de Supabase como backend.
> Creado: 2026-07-09

## Filosofía: Backend desacoplado del Storage

Separamos dos responsabilidades que hoy están mezcladas:

| Capa | Responsabilidad | Proveedor |
|------|----------------|-----------|
| **Backend** | Auth, dispositivos, sesiones, metadata | **Supabase** |
| **Storage** | Archivos (EPUBs, state.json, covers) | **Google Drive** (default), luego Supabase Storage como opción |

### ¿Por qué?

- **Google Drive** ya funciona para almacenar archivos. Los usuarios ya tienen libros ahí. No vamos a moverlos.
- Pero Drive NO es un backend: no tiene auth unificado, no tiene queries, no tiene RLS, no tiene realtime.
- **Supabase** nos da todo eso sin tener que construir un backend propio.
- La app sigue siendo **offline-first**: el storage (Drive) funciona sin conexión.
- El backend (Supabase) es para coordinación, no para datos pesados.

## Supabase: qué usamos

### Auth — Google OAuth unificado

Hoy:
- Desktop: OAuth PKCE manual con loopback URI (`tauri-plugin-oauth`)
- Android: Google One Tap (Credential Manager nativo)
- Cada plataforma maneja su propio token, no hay identidad compartida

Con Supabase:
- `supabase.auth.signInWithOAuth({ provider: 'google' })`
- Desktop y Android usan el mismo provider
- El `auth.uid` de Supabase es la identidad compartida entre plataformas
- Supabase maneja refresh tokens, sesiones expiradas, todo

```typescript
// Desktop - Tauri (futuro)
const { data, error } = await supabase.auth.signInWithOAuth({
  provider: 'google',
  options: { redirectTo: 'io.nextpage://auth/callback' }
})
```

```kotlin
// Android (futuro)
supabase.auth.signInWith(Google)
```

### Tabla: `devices`

Registro de dispositivos conectados a la cuenta del usuario.

> Estado real del esquema. La identidad del dueño NO es `user_id` (texto legacy),
> sino `supabase_user_id` (uuid de `auth.users`). Fuente:
> `supabase/migrations/20260709000001_create_devices.sql` y
> `supabase/migrations/20260709000002_add_supabase_user_id.sql`.

```sql
-- 20260709000001_create_devices.sql
create table public.devices (
  id          uuid primary key default gen_random_uuid(),
  user_id     text not null,              -- legacy: email o Google sub (texto), NO es FK
  hardware_id text not null,
  name        text not null,             -- "Mi PC", "Pixel 9"
  os          text not null,             -- "Windows 11", "Android 16"
  type        text not null default 'desktop'
                check (type in ('desktop', 'mobile', 'tablet', 'web')),
  last_active timestamptz not null default now(),
  created_at  timestamptz not null default now(),
  unique(user_id, hardware_id)           -- un dispositivo por hardware
);

-- 20260709000002_add_supabase_user_id.sql
-- Identidad de RLS. Puede ser NULL en dispositivos anónimos / legacy.
alter table public.devices add column supabase_user_id uuid;
create index idx_devices_supabase_user_id on devices(supabase_user_id);
```

Las políticas vigentes son las de `supabase/migrations/20261003000001_harden_rls_policies.sql`,
que endureció el RLS y eliminó el escape de dueño nulo:

```sql
alter table public.devices enable row level security;

create policy "users_select_own_devices" on public.devices
  for select
  using (auth.uid()::text = supabase_user_id::text);

-- Insert sin cambios: el registro anónimo de dispositivos sigue funcionando.
create policy "users_insert_own_devices" on public.devices
  for insert
  with check (
    auth.uid()::text = supabase_user_id::text
    or (supabase_user_id is null and auth.uid() is null)
  );

create policy "users_update_own_devices" on public.devices
  for update
  using (auth.uid()::text = supabase_user_id::text)
  with check (auth.uid()::text = supabase_user_id::text);

create policy "users_delete_own_devices" on public.devices
  for delete
  using (auth.uid()::text = supabase_user_id::text);
```

**Cambio de comportamiento intencional (no revertir):** tras
`20261003000001_harden_rls_policies.sql`, una fila con `supabase_user_id IS NULL`
queda **invisible e inmutable para todo cliente autenticado**. Ya no es
seleccionable, actualizable ni borrable por nadie. El único camino que sigue
permitido sin dueño es el **insert** anónimo; el escape de dueño nulo en
select/update/delete se eliminó deliberadamente. No hay backfill automático ni
mecanismo para reclamar esas filas.

### Tabla: `sessions`

Sesiones activas de Supabase Auth (autogestionada por Supabase, pero podemos extenderla con metadata).

### Realtime (opcional)

Para actualización en vivo de "última actividad" en la lista de dispositivos.

```typescript
// Escuchar cambios en dispositivos del usuario
supabase
  .channel('devices')
  .on('postgres_changes', { event: '*', schema: 'public', table: 'devices', filter: `user_id=eq.${userId}` },
    (payload) => { /* actualizar UI */ }
  )
  .subscribe()
```

## Storage: Google Drive (por ahora)

No cambiamos nada del storage actual. Google Drive sigue siendo el proveedor de archivos.

```typescript
// Interfaz abstracta para el futuro
interface StorageProvider {
  upload(path: string, data: Uint8Array): Promise<void>
  download(path: string): Promise<Uint8Array>
  list(prefix: string): Promise<string[]>
  delete(path: string): Promise<void>
}

// Implementación actual
class GoogleDriveStorage implements StorageProvider { /* ya existe */ }
```

## Roadmap

### Sprint 1 — Devices (ahora)

**Qué entra:**
- Crear proyecto Supabase
- Tabla `devices`
- Desktop: registro del dispositivo actual al iniciar sesión
- Desktop: heartbeat periódico (actualizar `last_active`)
- Desktop: UI "Dispositivos conectados" en Settings > Cuenta
- Desktop: botón "Cerrar sesión" para otros dispositivos (revocar)
- Auth sigue siendo el actual (no se toca)

**Qué NO entra:**
- No se migra el auth (sigue Google OAuth manual)
- No se toca Google Drive
- No se migran datos de lectura
- No hay Android ni web

**Datos mock vs reales:**
Los dispositivos se muestran con datos reales de Supabase desde el inicio. El dispositivo actual se auto-registra al abrir settings. No hay mock data.

### Sprint 2 — Migración de Auth

- Desktop y Android migran a Supabase Auth
- `auth.uid` pasa a ser la identidad compartida
- El userId de Drive (Google sub) se vincula al uid de Supabase
- Se cierra el gap de sesiones: se puede ver "conectado desde Android" y "conectado desde Desktop"

### Sprint 3+ — Storage Providers

- Implementación `SupabaseStorage` como provider alternativo
- Opción en settings: "Almacenamiento en la nube: Google Drive | Supabase | Local"
- Migración de archivos entre providers

### Sprint N — Features habilitadas por Supabase

- **Biblioteca compartida**: libros indexados en Postgres, visibles desde cualquier dispositivo sin escanear
- **Highlights en tiempo real**: resaltados sincronizados al instante via Realtime
- **Recomendaciones**: SQL analytics sobre qué lee el usuario
- **Compartir libros entre usuarios**: si sale multi-user
- **Push notifications**: FCM + Supabase Realtime para recordatorios de lectura
- **Web app**: NextPage web con el mismo backend

## Estructura de archivos (desktop)

```
src/lib/
├── features/
│   └── settings/
│       └── components/
│           └── ConnectedDevices.svelte    # UI de lista de dispositivos
├── services/
│   ├── supabase.ts                        # Cliente Supabase
│   └── devices.ts                         # CRUD de dispositivos (register, heartbeat, list, remove)
└── stores/
    └── devicesState.svelte.ts             # Estado reactivo de dispositivos
```

## Consideraciones técnicas

### Desktop detecta su propia info

```typescript
// Identificación del dispositivo actual
interface DeviceInfo {
  hardwareId: string     // único por máquina (MAC hash, machine-id, etc.)
  name: string           // hostname + "PC"
  os: string             // "Windows 11", "macOS 15", "Linux"
  type: 'desktop'
}

// Obtener desde Tauri
import { machineId } from '@tauri-apps/api/os'
import { type, version } from '@tauri-apps/api/os'
import { hostname } from '@tauri-apps/api/os'

const deviceInfo: DeviceInfo = {
  hardwareId: await machineId(),
  name: `${await hostname()} PC`,
  os: `${await type()} ${await version()}`,
  type: 'desktop',
}
```

### Heartbeat

El dispositivo actual envía un heartbeat a Supabase cada N minutos para actualizar `last_active`:

```typescript
// HeartbeatManager
class HeartbeatManager {
  private intervalId: ReturnType<typeof setInterval> | null = null
  
  start(deviceId: string) {
    // Actualizar cada 2 minutos
    this.intervalId = setInterval(() => {
      supabase.from('devices').update({ last_active: new Date().toISOString() }).eq('id', deviceId)
    }, 120_000)
  }
  
  stop() {
    if (this.intervalId) clearInterval(this.intervalId)
  }
}
```

## Preguntas abiertas

- [ ] ¿Firebase o Supabase para el proyecto? (decidido: Supabase)
- [ ] ¿El registro del dispositivo se hace al iniciar sesión o al abrir settings?
- [ ] ¿Heartbeat cada cuánto? (2 min parece bien)
- [ ] ¿Cómo detectar inactividad? (al cerrar la app se marca offline automáticamente)
- [ ] ¿El nombre del dispositivo es editable por el usuario?