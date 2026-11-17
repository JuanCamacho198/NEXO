# Design System and Tokens - NextPage

Este documento define el sistema de tokens para NextPage con enfoque dark-mode-first. Incluye equivalencias para Android (dp/sp) y Web/Tailwind (rem/px) para mantener consistencia entre plataformas.

## 1) Semantic Token Categories

Los tokens se organizan en cinco categorias semanticas:

1. **Color**: fondo, superficie, texto, borde, estados.
2. **Typography**: familias, tamanos, pesos, altura de linea.
3. **Spacing**: escala de espaciado para layout, gaps y paddings.
4. **Radius**: radios para controles, cards y formas tipo pill.
5. **Elevation / Shadows**: niveles de profundidad para jerarquia visual.

## 2) Color Tokens (Dark Mode First)

| Semantic Token | Android / Compose | Web / Tailwind | Uso |
| :--- | :--- | :--- | :--- |
| `color-background` | `#0A0A0A` | `#0A0A0A` | Fondo principal de la app. |
| `color-surface` | `#1E1E1E` | `#1E1E1E` | Tarjetas, paneles y contenedores. |
| `color-primary` | `#FFFFFF` | `#FFFFFF` | Texto principal y elementos activos. |
| `color-secondary` | `#E0E0E0` | `#E0E0E0` | Texto secundario y controles invertidos. |
| `color-tertiary` | `#9E9E9E` | `#9E9E9E` | Texto muted, placeholders e iconos inactivos. |
| `color-border` | `#424242` | `#424242` | Bordes y divisores. |
| `color-error` | `#FFB4B4` | `#FFB4B4` | Estados destructivos/error. |

## 3) Typography Tokens

### 3.1 Font Families

| Token | Android / Compose | Web / Tailwind |
| :--- | :--- | :--- |
| `font-serif` | `Newsreader` | `"Newsreader", "Times New Roman", serif` |
| `font-sans` | `Manrope` | `"Manrope", "Segoe UI", Arial, sans-serif` |

### 3.2 Type Scale (sp -> rem/px)

| Semantic Token | Android (sp) | Web (rem) | Web (px) | Uso |
| :--- | :--- | :--- | :--- | :--- |
| `text-display` | `40sp` | `2.5rem` | `40px` | Portadas y titulares grandes. |
| `text-title` | `32sp` | `2rem` | `32px` | Titulos principales de pantalla. |
| `text-heading` | `24sp` | `1.5rem` | `24px` | Subtitulos y cabeceras de seccion. |
| `text-body-lg` | `18sp` | `1.125rem` | `18px` | Texto de lectura destacado. |
| `text-body` | `16sp` | `1rem` | `16px` | Texto de interfaz y lectura base. |
| `text-body-sm` | `14sp` | `0.875rem` | `14px` | Texto secundario y metadatos. |
| `text-label` | `12sp` | `0.75rem` | `12px` | Labels y microcopy de UI. |

Guia de uso:
- Texto de lectura: `font-serif` (`Newsreader`).
- Elementos de UI (botones, navbar, etiquetas, formularios): `font-sans` (`Manrope`).

## 4) Spacing Tokens (dp -> rem/px)

Escala base: 4/8 para mantener ritmo visual consistente.

| Token | Android (dp) | Web (rem) | Web (px) | Uso |
| :--- | :--- | :--- | :--- | :--- |
| `spacing-xs` | `4dp` | `0.25rem` | `4px` | Separacion minima interna. |
| `spacing-sm` | `8dp` | `0.5rem` | `8px` | Gap entre icono y texto. |
| `spacing-md` | `16dp` | `1rem` | `16px` | Padding estandar de componentes. |
| `spacing-lg` | `24dp` | `1.5rem` | `24px` | Separacion entre bloques principales. |
| `spacing-xl` | `32dp` | `2rem` | `32px` | Margenes de seccion y aire visual. |

## 5) Radius Tokens (dp -> rem/px)

| Token | Android (dp) | Web (rem) | Web (px) | Uso |
| :--- | :--- | :--- | :--- | :--- |
| `radius-sm` | `4dp` | `0.25rem` | `4px` | Botones pequenos, badges. |
| `radius-md` | `8dp` | `0.5rem` | `8px` | Inputs y tarjetas compactas. |
| `radius-lg` | `16dp` | `1rem` | `16px` | Cards principales y modales. |
| `radius-pill` | `50dp` | `3.125rem` | `50px` | Chips y barras tipo capsula. |

## 6) Elevation / Shadow Tokens

| Token | Android (Compose) | Web (box-shadow) | Uso |
| :--- | :--- | :--- | :--- |
| `elevation-sm` | `2dp` | `0 1px 2px rgba(0, 0, 0, 0.35)` | Separacion sutil de controles sobre superficie. |
| `elevation-md` | `6dp` | `0 6px 18px rgba(0, 0, 0, 0.35)` | Cards y paneles flotantes. |
| `elevation-lg` | `12dp` | `0 12px 32px rgba(0, 0, 0, 0.45)` | Elementos destacados y overlays. |

## 7) Tailwind Mapping Quick Reference

- Colores: mapear a variables `--color-background`, `--color-surface`, `--color-primary`.
- Tipografia: mapear a variables `--font-serif`, `--font-sans`.
- Espaciado: usar escala basada en `rem` (`0.25`, `0.5`, `1`, `1.5`, `2`).
- Radius: usar `rounded-sm`, `rounded-md`, `rounded-xl`, y utilidades personalizadas para pill.
- Elevacion: usar `shadow-sm`, `shadow-md`, `shadow-lg` ajustadas al esquema oscuro.
