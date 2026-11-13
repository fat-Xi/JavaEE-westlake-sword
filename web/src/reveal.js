// 首次进入视口时播放一次。排序只重建活动列表，不重播其他区域。
export const reveal = {
  mounted(el) {
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    el.classList.add("prototype-reveal");
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          el.classList.add("visible");
          observer.disconnect();
        }
      },
      { threshold: 0.08 },
    );
    observer.observe(el);
    el.revealObserver = observer;
  },
  unmounted(el) {
    el.revealObserver?.disconnect();
  },
};
