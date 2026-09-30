# Nexo Desktop - Design Tokens

> Sistema de diseño para la aplicación de escritorio Nexo.
> Basado en `src/styles.css` + sistema Tailwind CSS.

---

## 1. Colores (Colors)

### Tema Oscuro (Default)

| Token                | Valor (HEX)          | Uso                              |
|----------------------|---------------------|----------------------------------|
| `--color-background` | `#08111f`           | Fondo principal de la app        |
| `--color-surface`    | `rgba(16,28,44,0.84)` | Tarjetas, paneles, modales       |
| `--color-panel-accent`| `rgba(73,212,255,0.08)` | Fondos con tinte azulado       |
| `--color-primary`    | `#f8fbff`           | Texto principal, headings        |
| `--color-secondary`   | `#dbe7f6`           | Texto secundario, labels        |
| `--color-tertiary`   | `#8fa3bf`           | Placeholder, texto muted          |
| `--color-border`     | `rgba(148,173,206,0.18)` | Bordes sutiles               |
| `--color-border-strong`| `rgba(148,173,206,0.3)` | Bordes activos/hover      |
| `--color-error`      | `#ff7b83`           | Errores, acciones destructivas |
| `--color-accent-blue`| `#49d4ff`           | Acentos, highlights, CTAs       |
| `--color-accent-soft`| `rgba(73,212,255,0.1)` | Fondos de énfasis          |
| `--color-text`        | `#f8fbff`           | Color de texto principal        |
| `--color-text-muted` | `#8fa3bf`           | Texto deshabilitado/hint        |

### Tema Claro (Light)

| Token                | Valor (HEX)          | Uso                              |
|----------------------|---------------------|----------------------------------|
| `--color-background` | `#eef2f7`           | Fondo principal de la app        |
| `--color-surface`    | `rgba(255,255,255,0.9)` | Tarjetas, paneles, modales       |
| `--color-panel-accent`| `rgba(0,110,200,0.06)` | Fondos con tinte azul          |
| `--color-primary`   | `#111827`            | Texto principal, headings       |
| `--color-secondary`  | `#1e3a5f`            | Texto secundario, labels       |
| `--color-tertiary`   | `#4a6888`            | Placeholder, texto muted         |
| `--color-border`     | `rgba(30,58,95,0.15)` | Bordes sutiles               |
| `--color-border-strong`| `rgba(30,58,95,0.28)` | Bordes activos/hover       |
| `--color-error`      | `#c0392b`            | Errores, acciones destructivas |
| `--color-accent-blue`| `#006ec8`            | Acentos, highlights, CTAs      |
| `--color-accent-soft`| `rgba(0,110,200,0.12)` | Fondos de énfasis           |
| `--color-text`       | `#111827`            | Color de texto principal        |
| `--color-text-muted`  | `#4a6888`            | Texto deshabilitado/hint       |

---

## 2. Tipografía (Typography)

| Token           | Valor                          | Uso                          |
|----------------|-------------------------------|------------------------------|
| `--font-sans`   | `"Manrope", "Segoe UI", Arial, sans-serif` | UI, botones, labels       |
| `--font-serif` | `"Newsreader", "Times New Roman", serif` | Lector,阅读体验        |

### Escalas Tipográficas (Tailwind)

| Clase        | Tamaño  | Line-height | Peso  | Uso                |
|--------------|---------|------------|-------|---------------------|
| `text-xs`    | 12px    | 1.5        | 400   | Metadata, hints     |
| `text-sm`   | 14px    | 1.5        | 400   | Cuerpo secundario   |
| `text-base` | 16px    | 1.5        | 400   | Cuerpo principal   |
| `text-lg`   | 18px    | 1.4        | 500   | Subtítulos         |
| `text-xl`   | 20px    | 1.4        | 600   | Titulos de seccion   |
| `text-2xl`  | 24px    | 1.3        | 700   | Titulos principales|
| `text-3xl`  | 30px    | 1.2        | 800   | Hero headers        |

---

## 3. Espaciado (Spacing)

Basado en escala Tailwind (4px base).

| Token          | Valor  | Clase       |
|---------------|-------|-------------|
| `--spacing-1` | 4px   | `p-1`       |
| `--spacing-2` | 8px   | `p-2`       |
| `--spacing-3` | 12px  | `p-3`       |
| `--spacing-4` | 16px  | `p-4`       |
| `--spacing-5` | 20px  | `p-5`       |
| `--spacing-6` | 24px  | `p-6`       |
| `--spacing-8` | 32px  | `p-8`       |
| `--spacing-10`| 40px  | `p-10`      |
| `--spacing-12`| 48px  | `p-12`      |
| `--spacing-16`| 64px  | `p-16`      |

---

## 4. Radios (Border Radius)

| Token          | Valor  | Uso                              |
|---------------|-------|----------------------------------|
| `--radius-sm`  | 4px   | Inputs, tags pequeños             |
| `--radius-md`  | 8px   | Botones, inputs,-dropdown         |
| `--radius-lg` | 12px  | Tarjetas, cards de libros       |
| `--radius-xl` | 16px  | Paneles grandes, modales      |
| `--radius-2xl`| 24px  | Cards principales, headers |

---

## 5. Sombras (Shadows)

| Token                 | Valor                                            | Uso                         |
|----------------------|--------------------------------------------------|----------------------------|
| `--shadow-soft`      | `0 4px 20px rgba(0, 0, 0, 0.2)`                 | Sombras generales            |
| `--shadow-glow`       | `0 0 15px rgba(73, 212, 255, 0.15)`              | Glow de acentos (dark)     |
| `--shadow-glow-hover`| `0 0 20px rgba(73, 212, 255, 0.3)`                | Glow en hover             |

---

## 6. Transiciones (Transitions)

```css
/* Aplicadas globalmente en * */
* {
  transition:
    background-color 0.35s ease,
    border-color 0.35s ease,
    color 0.25s ease,
    box-shadow 0.25s ease;
}
```

---

## 7. Componentes Estándar

### Botones

| Componente       | Fondo                    | Texto              | Borde                |
|----------------|-------------------------|-------------------|----------------------|
| `.btn-primary` | `var(--color-primary)`  | `var(--color-bg)` | none                 |
| `.btn-secondary`| `var(--color-surface)`    | `var(--color-primary)`| `1px solid var(--border)`|
| `.btn-ghost`  | transparent              | `var(--color-secondary)`| none            |

### Inputs

```css
input[type="text"] {
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-primary);
  border-radius: 8px;
  padding: 10px;
}
```

### Cards

```css
.card {
  background: var(--color-surface);
  border-radius: 24px;
  padding: 16px;
  box-shadow: 0 28px 80px rgba(3, 10, 20, 0.46);
  margin-bottom: 16px;
}
```

### Book List Items

```css
.book-list button {
  width: 100%;
  text-align: left;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  border-radius: 10px;
  padding: 10px;
  cursor: pointer;
}

.book-list button.selected {
  border-color: var(--color-primary);
  background: color-mix(in srgb, var(--color-primary) 8%, var(--color-surface));
}
```

---

## 8. Layout Grid

```css
.workspace {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 24px;
}

@media (max-width: 900px) {
  .workspace {
    grid-template-columns: 1fr;
  }
}
```

---

## 9. Utilities

### Selection Color

```css
::selection {
  background: rgba(73, 212, 255, 0.28);
  color: var(--color-text);
}
```

### Focus Ring

```css
:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(148, 236, 255, 0.22);
}
```

### Reduced Motion

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 1ms !important;
    animation-iteration-count: 1 !important;
    scroll-behavior: auto !important;
    transition-duration: 1ms !important;
  }
}
```

---

## 10. Paleta de Colores Extendida

### Colores Semánticos

| Nombre      | Dark (HEX)  | Light (HEX) | Uso                      |
|-------------|-------------|------------|--------------------------|
| `accent`    | `#49d4ff`   | `#006ec8`  | Links, acentos principales|
| `success`   | `#2dd4bf`   | `#059669`  | Estados exitosos       |
| `warning`   | `#fbbf24`   | `#d97706`  | Avisos, advertencias  |
| `error`     | `#ff7b83`   | `#c0392b`  | Errores, acciones risk|
| `info`      | `#60a5fa`   | `#2563eb`  | Información            |

### Colores de Resaltado (Highlights)

| Color           | HEX       | Uso                              |
|----------------|----------|----------------------------------|
| Amarillo       | `#FDE047` | Resaltados amarillos             |
| Verde          | `#2dd4bf` | Resaltados verdes                |
| azul/Cyan      | `#38BDF8` | Resaltados azules                |
| Violeta        | `#A78BFA` | Resaltados violetas              |
| Naranja        | `#FB923C` | Resaltados naranjas             |
| Rosa           | `#F472B6` | Resaltados rosas                |

---

---

## 11. Pen Sync Drift Note — `pen-desktop-home-sync` (2026-08-26)

> Sync `design/nexo-desktop.pen` v2.15 (1240 nodes → 17 frames, `GImmK` 1200w preserved) to code truth via `scripts/sync-pen-home.mjs`.

### Variables 7 → 10 (hex-only `type:color`)

| Pen `$var` | HEX | App source |
|---|---|---|
| `$surfaceHover` | `#1E293B` | `tokens.css --color-surface` / `--color-panel-accent` dark |
| `$textMuted` | `#64748B` | `tokens.css --color-tertiary` / `--color-text-muted` |
| `$error` | `#EF4444` | `tokens.css --color-error` (pen keeps hex-only; app has `rgba` variants) |

All `variables` entries are `{type:"color", value:"#hex"}` validated by `/^#[0-9A-Fa-f]{6}$/` — `rgba()`/gradients rejected.

### `GImmK.layoutSec` — reusable placement

New child `layoutSec` (child of `GImmK`, `width:fill_container`, `gap:16`, vertical) hosts 3 `reusable:true` nodes:

| Reusable | Geometry | Mapping |
|---|---|---|
| `Sidebar` | `w:260` `fill:$bgBase` `stroke:$border` `r:1` `pad:16` `gap:12` | `AppSidebar.svelte` `w-64 (260px) ↔ w-18 (72px)` — pen static 260, collapsed 72 documented in `layoutSec.description` |
| `CustomTitleBar` | `h:36` `fill:$bgPanel` `layout:horizontal` `justify:space_between` | `CustomTitlebar.svelte` `h-9 (36px)` — `left NP32 Nexo | center flex1 data-tauri-drag-region | right 3×32w` |
| `LibraryGrid` | `clone:DN5F1` `layout:grid` `gap:16` `pad:16` `cards 200×280 r:12 ref→vVEie` | `LibraryShelfScreen.svelte` `ShelfGrid` — `grid 4 cols` wrapper |

`layoutSec` description field: `w-64↔w-18 (260→72) mapping — Sidebar 260 static, collapsed 72 documented for impl`.

### Out-of-scope (intentional drift)

- Full 31-token mirror, gradients/shadows/light theme, `Inter→Manrope` swap — deferred.
- Runtime `src/` / `src-tauri/` unchanged — pen-only sync.

### Verification

`bun run scripts/sync-pen-home.mjs` → `inserts:0` on re-run, `GImmK.width:1200`, `Home / Desktop 1280×800 @ x1400 y800` non-overlapping, `ZnFqZ` repaired, `.bak` + `JSON.parse` guard.

*Última actualización: 2026-08-26 — pen-desktop-home-sync*