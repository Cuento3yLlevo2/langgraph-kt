import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import { h } from 'vue'
import Banner from './Banner.vue'
import Ribbon from './Ribbon.vue'
import './fonts.css'
import './custom.css'

// The default theme of VitePress with the look of the Telar brand: the woven band above every
// page, and the banner of the home page in place of the default hero.
export default {
  extends: DefaultTheme,
  Layout: () =>
    h(DefaultTheme.Layout, null, {
      'layout-top': () => h(Ribbon),
      'home-hero-before': () => h(Banner),
    }),
} satisfies Theme
