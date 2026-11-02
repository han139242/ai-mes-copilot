<template>
  <!-- 登录页专用台灯场景；使用本地矢量和柔光，不依赖外部图片服务。 -->
  <svg class="lamp-scene" viewBox="75 65 580 505" fill="none" aria-hidden="true">
    <defs>
      <linearGradient id="scene-stem" x1="330" y1="175" x2="450" y2="470" gradientUnits="userSpaceOnUse">
        <stop stop-color="#FBFDFF" />
        <stop offset=".27" stop-color="#E6EEF9" />
        <stop offset=".48" stop-color="#92AAC7" />
        <stop offset=".65" stop-color="#D5E3F3" />
        <stop offset="1" stop-color="#B0C5DE" />
      </linearGradient>
      <linearGradient id="scene-head" x1="193" y1="139" x2="412" y2="191" gradientUnits="userSpaceOnUse">
        <stop stop-color="#FFFFFF" />
        <stop offset=".48" stop-color="#F3F7FF" />
        <stop offset="1" stop-color="#ACC3E1" />
      </linearGradient>
      <linearGradient id="scene-edge" x1="186" y1="165" x2="423" y2="188" gradientUnits="userSpaceOnUse">
        <stop stop-color="#C6D5E6" />
        <stop offset=".4" stop-color="#ECF3FD" />
        <stop offset="1" stop-color="#94AFD0" />
      </linearGradient>
      <linearGradient id="scene-base" x1="290" y1="481" x2="535" y2="524" gradientUnits="userSpaceOnUse">
        <stop stop-color="#EDF3FD" />
        <stop offset=".23" stop-color="#D3E0F2" />
        <stop offset=".7" stop-color="#B2C9E5" />
        <stop offset="1" stop-color="#88A9D2" />
      </linearGradient>
      <linearGradient id="scene-base-top" x1="327" y1="465" x2="489" y2="513" gradientUnits="userSpaceOnUse">
        <stop stop-color="#FFFFFF" />
        <stop offset=".6" stop-color="#EBF2FD" />
        <stop offset="1" stop-color="#C4D6ED" />
      </linearGradient>
      <linearGradient id="scene-beam" x1="286" y1="185" x2="257" y2="516" gradientUnits="userSpaceOnUse">
        <stop stop-color="#FFE8AF" stop-opacity=".42" />
        <stop offset=".55" stop-color="#FFDF98" stop-opacity=".14" />
        <stop offset="1" stop-color="#FFFFFF" stop-opacity="0" />
      </linearGradient>
      <radialGradient id="scene-pool">
        <stop stop-color="#FFE8B7" stop-opacity=".24" />
        <stop offset=".65" stop-color="#D8C599" stop-opacity=".08" />
        <stop offset="1" stop-color="#E2EBF9" stop-opacity="0" />
      </radialGradient>
      <radialGradient id="scene-halo">
        <stop stop-color="#88D3C2" stop-opacity=".14" />
        <stop offset="1" stop-color="#8BC8BD" stop-opacity="0" />
      </radialGradient>
      <filter id="scene-shadow" x="-50%" y="-200%" width="200%" height="500%">
        <feGaussianBlur stdDeviation="15" />
      </filter>
      <filter id="scene-glow" x="-50%" y="-200%" width="200%" height="500%">
        <feGaussianBlur stdDeviation="10" />
      </filter>
    </defs>
    <ellipse cx="391" cy="318" rx="290" ry="255" fill="url(#scene-halo)" />
    <path
      d="M-32 420C178 362 172 510 388 512c116 1 214-39 347-4"
      stroke="white"
      stroke-opacity=".035"
      stroke-width="36"
    />
    <ellipse cx="354" cy="525" rx="270" ry="63" fill="url(#scene-pool)" />
    <path d="m204 184 193 1 117 327H75L204 184Z" fill="url(#scene-beam)" />
    <ellipse cx="419" cy="534" rx="131" ry="17" fill="#000E17" fill-opacity=".65" filter="url(#scene-shadow)" />
    <!-- 圆形底座和灯臂的分层明暗表现哑光金属材质。 -->
    <path
      d="M292 488c0-18 55-32 123-32s123 14 123 32v18c0 18-55 33-123 33s-123-15-123-33v-18Z"
      fill="url(#scene-base)"
    />
    <ellipse cx="415" cy="488" rx="123" ry="32" fill="url(#scene-base-top)" />
    <path d="M300 482c19-18 71-27 114-27 50 0 88 6 111 20" stroke="white" stroke-opacity=".85" />
    <path d="M417 475V326c0-24-3-39-16-58l-65-99" stroke="#8FAACB" stroke-width="19" stroke-linecap="round" />
    <path d="M413 474V325c0-24-3-39-16-58l-65-99" stroke="url(#scene-stem)" stroke-width="15" stroke-linecap="round" />
    <path
      d="M409 472V324c0-23-4-39-16-56"
      stroke="white"
      stroke-opacity=".65"
      stroke-width="2"
      stroke-linecap="round"
    />
    <ellipse cx="413" cy="479" rx="15" ry="4" fill="#AFC3DC" />
    <g transform="rotate(11 303 168)">
      <path
        d="M182 157c0-20 54-36 121-36s121 16 121 36v17c0 19-54 34-121 34s-121-15-121-34v-17Z"
        fill="url(#scene-edge)"
      />
      <ellipse cx="303" cy="157" rx="121" ry="36" fill="url(#scene-head)" />
      <path
        d="M195 150c20-19 89-31 145-23"
        stroke="white"
        stroke-opacity=".95"
        stroke-width="2"
        stroke-linecap="round"
      />
      <ellipse cx="303" cy="178" rx="111" ry="25" fill="#C3CFD8" />
      <ellipse cx="303" cy="179" rx="102" ry="20" fill="#FFFBE8" />
      <ellipse cx="303" cy="181" rx="92" ry="14" fill="#FFFDF4" />
      <path d="M211 182c30 12 115 22 183 0" stroke="#FFF6CD" stroke-width="9" filter="url(#scene-glow)" />
    </g>
    <ellipse cx="376" cy="484" rx="10" ry="3" stroke="#A7BBD5" stroke-width="1.2" />
    <path d="M376 479v5" stroke="#A7BBD5" stroke-width="1.2" stroke-linecap="round" />
    <circle cx="447" cy="490" r="2" fill="#71C7AE" />
    <circle cx="459" cy="490" r="2" fill="#BECFE9" />
    <circle cx="471" cy="489" r="2" fill="#BECFE9" />
  </svg>
</template>

<style scoped>
.lamp-scene {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
}
</style>
