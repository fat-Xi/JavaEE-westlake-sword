<script setup>
defineProps({ down: Boolean, filled: Boolean });
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    :fill="filled ? 'currentColor' : 'none'"
    stroke="currentColor"
    stroke-width="1.65"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <g :transform="down ? 'translate(0 24) scale(1 -1)' : undefined">
      <rect x="3" y="10" width="4" height="10" rx="1.2" />
      <path
        d="m7 11 4.5-7a1.4 1.4 0 0 1 2.5.9 4 4 0 0 1-.2 1.5L13 10h5a2 2 0 0 1 2 2.4l-1.2 5.9a2 2 0 0 1-2 1.7h-4.6a4 4 0 0 1-1.5-.3L7 18.2"
      />
    </g>
  </svg>
</template>
