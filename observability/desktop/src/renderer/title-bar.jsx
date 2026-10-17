// title-bar.jsx — the window's title strip (#577). The window is
// `titleBarStyle: "hiddenInset"` (src/main.js), so this thin strip is where
// the title was: right of the macOS traffic lights, a drag region on every
// screen. Its leftmost item is the repo switcher (no-drag), which takes the
// flexible space so the notice, if any, stays right-aligned. No badge here;
// the counts are in the switcher's dropdown. The selected repo is not drawn
// again here (#1600): the switcher already shows it. The OS window title
// (titleFor, via setTitle/document.title) is unaffected.

import { RepoSwitcher } from "./repo-switcher.jsx";

export const TitleBar = ({ selection, open, onToggle, onPick, onManage, notice }) => (
  <div className="titleBar">
    <RepoSwitcher selection={selection} open={open} onToggle={onToggle} onPick={onPick} onManage={onManage} />
    {notice && <span className="titleBarNotice" title={notice}>{notice}</span>}
  </div>
);
