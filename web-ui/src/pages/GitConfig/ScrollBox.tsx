import React from "react";

/**
 * A scrolling table or list that shows when more is below: a fade along its bottom edge while
 * rows are hidden there, gone once the last row is in view. `rowSelector` counts the rows on
 * screen for a list whose length the caller does not hold (the Git ignore tree).
 */
const ScrollBox = ({
  className,
  wrapClassName,
  rowSelector,
  onCount,
  children,
}: {
  className: string;
  // For a box that is a flex item: its flex rules go on the wrapper.
  wrapClassName?: string;
  rowSelector?: string;
  onCount?: (n: number) => void;
  children: React.ReactNode;
}) => {
  const ref = React.useRef<HTMLDivElement>(null);
  const [fade, setFade] = React.useState<{ right: number } | null>(null);

  const update = React.useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const more = el.scrollHeight - el.scrollTop - el.clientHeight > 2;
    // Clear of the scrollbar, which the fade would otherwise wash out.
    setFade(more ? { right: el.offsetWidth - el.clientWidth } : null);
    if (rowSelector && onCount) {
      onCount(el.querySelectorAll(rowSelector).length);
    }
  }, [rowSelector, onCount]);

  React.useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    update();
    const resize = new ResizeObserver(update);
    resize.observe(el);
    // Rows arriving or a folder opening changes the content's height, not the box's.
    const rows = new MutationObserver(update);
    rows.observe(el, { childList: true, subtree: true });
    return () => {
      resize.disconnect();
      rows.disconnect();
    };
  }, [update]);

  return (
    <div className={`gitcfg-scroll-wrap ${wrapClassName ?? ""}`}>
      <div ref={ref} className={className} onScroll={update}>
        {children}
      </div>
      {fade ? (
        <div
          className="gitcfg-scroll-fade"
          style={{ right: fade.right }}
          aria-hidden="true"
        />
      ) : null}
    </div>
  );
};

/** The row count beside a tab's title. */
export const RowCount = ({ n, noun }: { n: number; noun: string }) => (
  <span className="gitcfg-row-count">
    {n} {n === 1 ? noun : `${noun}s`}
  </span>
);

export default ScrollBox;
