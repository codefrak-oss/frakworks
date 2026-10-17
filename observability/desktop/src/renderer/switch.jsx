// A page's switch: a checkbox and its label, as Config > Repos' pages and the
// account Edit… modal's Enabled row draw it.

/** A page's switch: a checkbox and its label. */
export const Switch = ({ label, checked, disabled, onChange }) => (
  <label className="acToggle erSwitch">
    <input type="checkbox" aria-label={label} checked={!!checked} disabled={disabled} onChange={() => onChange(!checked)} />
    {label}</label>
);
