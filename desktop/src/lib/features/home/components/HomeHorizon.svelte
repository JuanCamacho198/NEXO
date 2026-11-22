<script lang="ts">
  // Unique per instance: SVG paint-server ids are document-global.
  const instanceId = $props.id();
  const uid = `nexo-horizon-${instanceId}`;

  const CX = 1060;
  const CY = 640;
  const R = 560;

  type Light = { x: number; y: number; r: number; o: number };

  /**
   * Night-side "city lights": a fixed, seeded scatter (identical on every
   * render) that thickens toward the lit rim and thins into the dark side, so
   * it reads as settlements catching the light rather than a dot grid.
   */
  function scatterLights(count: number, seed: number): Light[] {
    let s = seed;
    const rand = (): number => {
      s = (s * 1664525 + 1013904223) % 4294967296;
      return s / 4294967296;
    };
    const out: Light[] = [];
    for (let tries = 0; out.length < count && tries < 20000; tries++) {
      const x = 520 + rand() * 480;
      const y = 70 + rand() * 250;
      const depth = R - Math.hypot(x - CX, y - CY);
      if (depth < 3) continue;
      const nearRim = Math.exp(-depth / 120);
      if (rand() > nearRim * 0.85 + 0.04) continue;
      out.push({
        x: Math.round(x * 10) / 10,
        y: Math.round(y * 10) / 10,
        r: Math.round((0.5 + rand() * (0.6 + nearRim)) * 100) / 100,
        o: Math.round((0.18 + nearRim * 0.7 * rand() + nearRim * 0.12) * 100) / 100,
      });
    }
    return out;
  }

  const lights = scatterLights(320, 20261121);
</script>

<!--
  Decorative backdrop for the Home "continue reading" hero: a night planet whose
  rim is lit by the NEXO ember, with a few arcs linking points on its surface
  (the "nexo" between devices). Pure geometry, every colour from tokens, so it
  follows the theme. The left part fades into the hero surface so the title and
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
      <circle cx={CX} cy={CY} r={R} />
    </clipPath>
    <linearGradient
      id="{uid}-rim"
      x1="540"
      y1="320"
      x2="1000"
      y2="70"
      gradientUnits="userSpaceOnUse"
    >
      <stop offset="0" stop-color="var(--color-brand)" stop-opacity="0" />
      <stop offset="0.5" stop-color="var(--color-brand)" stop-opacity="0.6" />
      <stop offset="1" stop-color="var(--color-brand-start)" stop-opacity="1" />
    </linearGradient>
    <radialGradient id="{uid}-dayside" cx="1010" cy="90" r="430" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="var(--color-brand)" stop-opacity="0.3" />
      <stop offset="0.45" stop-color="var(--color-brand)" stop-opacity="0.08" />
      <stop offset="1" stop-color="var(--color-brand)" stop-opacity="0" />
    </radialGradient>
    <radialGradient id="{uid}-glow" cx="1000" cy="30" r="420" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="var(--color-brand)" stop-opacity="0.42" />
      <stop offset="0.5" stop-color="var(--color-brand)" stop-opacity="0.1" />
      <stop offset="1" stop-color="var(--color-brand)" stop-opacity="0" />
    </radialGradient>
    <linearGradient id="{uid}-fade" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="var(--color-horizon-fade)" stop-opacity="1" />
      <stop offset="0.4" stop-color="var(--color-horizon-fade)" stop-opacity="0.92" />
      <stop offset="0.66" stop-color="var(--color-horizon-fade)" stop-opacity="0" />
    </linearGradient>
    <filter id="{uid}-blur-sm" x="-20%" y="-20%" width="140%" height="140%">
      <feGaussianBlur stdDeviation="6" />
    </filter>
    <filter id="{uid}-blur-lg" x="-30%" y="-30%" width="160%" height="160%">
      <feGaussianBlur stdDeviation="22" />
    </filter>
  </defs>

  <rect width="1000" height="320" fill="url(#{uid}-glow)" />

  <!-- Atmosphere: a wide soft halo under a tight bright rim. -->
  <circle
    cx={CX}
    cy={CY}
    r={R + 6}
    fill="none"
    stroke="url(#{uid}-rim)"
    stroke-width="44"
    filter="url(#{uid}-blur-lg)"
    opacity="0.45"
  />

  <circle cx={CX} cy={CY} r={R} fill="var(--color-horizon-planet)" />

  <g clip-path="url(#{uid}-planet)">
    <rect width="1000" height="320" fill="url(#{uid}-dayside)" />
    <g fill="none" stroke="var(--color-horizon-grid)" stroke-width="1">
      <ellipse cx={CX} cy={CY} rx="140" ry={R} />
      <ellipse cx={CX} cy={CY} rx="300" ry={R} />
      <ellipse cx={CX} cy={CY} rx="440" ry={R} />
      <ellipse cx={CX} cy="400" rx={R} ry="70" />
      <ellipse cx={CX} cy="270" rx={R} ry="95" />
      <ellipse cx={CX} cy="160" rx={R} ry="120" />
    </g>
    <g fill="var(--color-horizon-lights)">
      {#each lights as light, index (index)}
        <circle cx={light.x} cy={light.y} r={light.r} opacity={light.o} />
      {/each}
    </g>
  </g>

  <circle
    cx={CX}
    cy={CY}
    r={R}
    fill="none"
    stroke="url(#{uid}-rim)"
    stroke-width="10"
    filter="url(#{uid}-blur-sm)"
    opacity="0.9"
  />
  <circle cx={CX} cy={CY} r={R} fill="none" stroke="url(#{uid}-rim)" stroke-width="1.5" />

  <g fill="none" stroke="var(--color-brand)" stroke-linecap="round" opacity="0.55">
    <path d="M742 236 Q 820 120 930 150" stroke-width="1.1" />
    <path d="M820 300 Q 900 210 985 248" stroke-width="0.9" />
  </g>
  <g fill="var(--color-brand-start)">
    <circle cx="742" cy="236" r="2.4" />
    <circle cx="930" cy="150" r="2.4" />
    <circle cx="820" cy="300" r="2" />
    <circle cx="985" cy="248" r="2" />
  </g>

  <rect width="1000" height="320" fill="url(#{uid}-fade)" />
</svg>
