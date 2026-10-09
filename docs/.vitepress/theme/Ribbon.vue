<!-- The llengües pattern of docs/brand as a woven band along the top of every page. -->
<template>
  <div class="telar-ribbon" aria-hidden="true" :style="{ backgroundImage: `url(${withBase('/llengues-pattern.svg')})` }" />
</template>

<script setup lang="ts">
import { withBase } from 'vitepress'
</script>
