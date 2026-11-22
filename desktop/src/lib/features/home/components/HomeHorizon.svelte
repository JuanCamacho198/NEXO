<script lang="ts">
  // Unique per instance: SVG paint-server ids are document-global.
  const instanceId = $props.id();
  const uid = `nexo-horizon-${instanceId}`;
</script>

<!--
  Decorative backdrop for the Home "continue reading" hero: a night planet whose
  rim is lit by the NEXO ember, with a few arcs linking points on its surface
  (the "nexo" between devices). Pure geometry, every colour from tokens, so it
  follows the theme. The left third fades into the hero surface so the title and
  progress always sit on a calm field.
-->
<svg
  class="pointer-events-none absolute inset-0 h-full w-full"
  viewBox="0 0 1000 320"
  preserveAspectRatio="xMaxYMid slice"
  aria-hidden="true"
  focusable="false"
>
  <defs>
    <clipPath id="{uid}-planet">
      <circle cx="1060" cy="640" r="560" />
    </clipPath>
    <linearGradient
      id="{uid}-rim"
      x1="560"
      y1="320"
      x2="1000"
      y2="60"
      gradientUnits="userSpaceOnUse"
    >
      <stop offset="0" stop-color="var(--color-brand)" stop-opacity="0" />
      <stop offset="0.55" stop-color="var(--color-brand)" stop-opacity="0.55" />
      <stop offset="1" stop-color="var(--color-brand-start)" stop-opacity="1" />
    </linearGradient>
    <radialGradient id="{uid}-light-mask" cx="960" cy="110" r="420" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#fff" stop-opacity="1" />
      <stop offset="1" stop-color="#fff" stop-opacity="0" />
    </radialGradient>
    <mask id="{uid}-lights-mask">
      <rect width="1000" height="320" fill="url(#{uid}-light-mask)" />
    </mask>
    <pattern id="{uid}-dots" width="11" height="11" patternUnits="userSpaceOnUse">
      <circle cx="2" cy="2" r="1.1" fill="var(--color-horizon-lights)" />
    </pattern>
    <radialGradient id="{uid}-glow" cx="1000" cy="40" r="380" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="var(--color-brand)" stop-opacity="0.38" />
      <stop offset="1" stop-color="var(--color-brand)" stop-opacity="0" />
    </radialGradient>
    <linearGradient id="{uid}-fade" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="var(--color-horizon-fade)" stop-opacity="1" />
      <stop offset="0.42" stop-color="var(--color-horizon-fade)" stop-opacity="0.9" />
      <stop offset="0.7" stop-color="var(--color-horizon-fade)" stop-opacity="0" />
    </linearGradient>
    <filter id="{uid}-blur" x="-20%" y="-20%" width="140%" height="140%">
      <feGaussianBlur stdDeviation="9" />
    </filter>
  </defs>

  <rect width="1000" height="320" fill="url(#{uid}-glow)" />

  <circle cx="1060" cy="640" r="560" fill="var(--color-horizon-planet)" />

  <g clip-path="url(#{uid}-planet)" fill="none" stroke="var(--color-horizon-grid)" stroke-width="1">
    <ellipse cx="1060" cy="640" rx="120" ry="560" />
    <ellipse cx="1060" cy="640" rx="260" ry="560" />
    <ellipse cx="1060" cy="640" rx="400" ry="560" />
    <ellipse cx="1060" cy="640" rx="520" ry="560" />
    <ellipse cx="1060" cy="380" rx="560" ry="60" />
    <ellipse cx="1060" cy="250" rx="560" ry="80" />
    <ellipse cx="1060" cy="150" rx="560" ry="110" />
  </g>

  <g clip-path="url(#{uid}-planet)" mask="url(#{uid}-lights-mask)" opacity="0.55">
    <rect width="1000" height="320" fill="url(#{uid}-dots)" />
  </g>

  <circle
    cx="1060"
    cy="640"
    r="560"
    fill="none"
    stroke="url(#{uid}-rim)"
    stroke-width="14"
    filter="url(#{uid}-blur)"
    opacity="0.8"
  />
  <circle cx="1060" cy="640" r="560" fill="none" stroke="url(#{uid}-rim)" stroke-width="1.6" />

  <g fill="none" stroke="var(--color-brand)" stroke-linecap="round" opacity="0.6">
    <path d="M742 236 Q 820 120 930 150" stroke-width="1.2" />
    <path d="M820 300 Q 900 210 985 248" stroke-width="1" />
  </g>
  <g fill="var(--color-brand-start)">
    <circle cx="742" cy="236" r="2.6" />
    <circle cx="930" cy="150" r="2.6" />
    <circle cx="820" cy="300" r="2.2" />
    <circle cx="985" cy="248" r="2.2" />
  </g>

  <rect width="1000" height="320" fill="url(#{uid}-fade)" />
</svg>
