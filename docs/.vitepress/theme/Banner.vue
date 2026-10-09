<!-- The top of the home page. Its texts and buttons come from `banner` in the front matter of index.md. -->
<template>
  <section v-if="banner" class="telar-banner">
    <div class="telar-banner__inner">
      <div class="telar-banner__text">
        <p class="telar-label">{{ banner.label }}</p>
        <h1 class="telar-banner__title">{{ banner.title }}</h1>
        <p class="telar-banner__tagline">{{ banner.tagline }}</p>
        <div class="telar-banner__actions">
          <a
            v-for="action in banner.actions"
            :key="action.link"
            class="telar-button"
            :class="{ 'telar-button--primary': action.primary }"
            :href="isExternal(action.link) ? action.link : withBase(action.link)"
          >{{ action.text }}</a>
        </div>
      </div>
      <div class="telar-banner__art" :style="{ backgroundImage: `url(${withBase('/llengues-pattern.svg')})` }">
        <div class="telar-banner__loom">
          <img :src="withBase('/telar-loom-mark.svg')" :alt="banner.imageAlt" width="252" height="252">
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

interface Action {
  text: string
  link: string
  primary?: boolean
}

interface BannerText {
  label: string
  title: string
  tagline: string
  imageAlt: string
  actions: Action[]
}

const { frontmatter } = useData()
const banner = computed<BannerText | undefined>(() => frontmatter.value.banner)
const isExternal = (link: string) => /^https?:/.test(link)
</script>
