/**
 * DESIGN TOKENS — Color Palette
 * Semantic tokens with comments for AI understanding
 *
 * Naming convention: semantic (primary, secondary) + role (text, surface, border)
 * Avoid: #FF0000, 'red', 'blue' — always use tokens
 */

/* ============================================
 * DARK THEME (default)
 * ============================================ */
export const darkTheme = {
  // Core surfaces
  background: '#08111f', // Main app background
  surface: 'rgba(16, 28, 44, 0.84)', // Cards, panels
  panelAccent: 'rgba(73, 212, 255, 0.08)', // Active/hover panels

  // Text hierarchy
  primary: '#f8fbff', // Headlines, primary content
  secondary: '#dbe7f6', // Body text, secondary content
  tertiary: '#8fa3bf', // Captions, disabled, hints

  // Semantic
  error: '#ff7b83', // Errors, destructive actions
  accentBlue: '#49d4ff', // Links, highlights, focus
  accentSoft: 'rgba(73, 212, 255, 0.1)', // Subtle accents

  // Borders
  border: 'rgba(148, 173, 206, 0.18)', // Subtle dividers
  borderStrong: 'rgba(148, 173, 206, 0.3)', // Input borders, active

  // Shadows
  shadowSoft: '0 4px 20px rgba(0, 0, 0, 0.2)',
  shadowGlow: '0 0 15px rgba(73, 212, 255, 0.15)',
  shadowGlowHover: '0 0 20px rgba(73, 212, 255, 0.3)',
} as const;

/* ============================================
 * LIGHT THEME
 * ============================================ */
export const lightTheme = {
  // Core surfaces
  background: '#eef2f7',
  surface: 'rgba(255, 255, 255, 0.9)',
  panelAccent: 'rgba(0, 110, 200, 0.06)',

  // Text hierarchy
  primary: '#111827', // Headlines
  secondary: '#1e3a5f', // Body
  tertiary: '#4a6888', // Captions

  // Semantic
  error: '#c0392b',
  accentBlue: '#006ec8',
  accentSoft: 'rgba(0, 110, 200, 0.12)',

  // Borders
  border: 'rgba(30, 58, 95, 0.15)',
  borderStrong: 'rgba(30, 58, 95, 0.28)',

  // Shadows
  shadowSoft: '0 4px 20px rgba(0, 0, 0, 0.08)',
  shadowGlow: '0 0 15px rgba(0, 110, 200, 0.14)',
  shadowGlowHover: '0 0 20px rgba(0, 110, 200, 0.26)',
} as const;

/* ============================================
 * SPACING
 * ============================================ */
export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
} as const;

/* ============================================
 * RADIUS
 * ============================================ */
export const radius = {
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
} as const;

/* ============================================
 * TYPOGRAPHY
 * ============================================ */
export const font = {
  sans: '"Manrope", "Segoe UI", Arial, sans-serif',
  serif: '"Newsreader Variable", "Newsreader", "Times New Roman", serif',
} as const;

/* ============================================
 * USAGE GUIDE FOR AI
 * ============================================ */
/**
 * DO:
 *   - Use semantic tokens: var(--color-primary), not #f8fbff
 *   - Match text/bg pairs: background + primary text
 *   - Use surface for cards, panels
 *   - Use accentSoft for subtle hovers
 *
 * DON'T:
 *   - Use hardcoded colors (#ff0000, 'red')
 *   - Mix dark/light colors
 *   - Use tertiary for main content
 *   - Use borderStrong for dividers
 */
