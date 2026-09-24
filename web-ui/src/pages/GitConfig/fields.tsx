import React from "react";
import {
  SelectInput as BaseSelectInput,
  TextArea as BaseTextArea,
  TextInput as BaseTextInput,
} from "../../webui";

/**
 * Label-painting wrappers around the platform inputs.
 *
 * The platform's TextInput/SelectInput/TextArea accept a `label` and render it ONLY into MUI's
 * notched-outline legend, which ships at opacity 0 — it is there to cut the notch, not to be
 * read. Measured on 8.3.8: every bare field on these tabs had its label in the DOM and invisible
 * on screen, and forcing the legend opaque only clips it against the border.
 *
 * These render the label above the control and pass everything else straight through, so every
 * existing `label=` call site works unchanged. `label` is deliberately NOT forwarded — without it
 * the notch stays closed and the outline is unbroken.
 *
 * The visible span is a sibling, not a wrapping <label>, so it carries no programmatic
 * association of its own — axe's `label` rule (4.1.2) / `aria-input-field-name` flags the real
 * control as nameless. Measured on 8.3.8, the three Base components disagree on where an extra
 * prop lands, so each needs its own route to the real element:
 *  - TextArea spreads unknown props straight onto its own `<textarea>` — a plain `aria-label`
 *    reaches it.
 *  - SelectInput forwards `inputProps` to MUI's `<Select>`, which applies it to the real
 *    `[role=combobox]` div — a top-level `aria-label` instead lands on the role-less
 *    `OutlinedInput` root and trips `aria-prohibited-attr`.
 *  - TextInput's own render always overwrites `inputProps` with just `{maxLength, minLength}`
 *    (a hard-coded object, not merged with what is passed in), so nothing set through props ever
 *    reaches its `<input>`. It does thread an incoming `id` onto that `<input>` unchanged
 *    (verified 8.3.8) — so a real `<label htmlFor>` association is the only route left, and the
 *    visible span becomes that label instead of an aria-label add-on.
 */
type Props = { label?: React.ReactNode } & Record<string, unknown>;

const slug = (s: string) =>
  s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "");

const labelled = (Base: React.ComponentType<Record<string, unknown>>) =>
  function Labelled({ label, ...rest }: Props) {
    if (!label) return <Base {...rest} />;
    return (
      <div className="gitcfg-field">
        <span className="gitcfg-field-label">{label}</span>
        <Base
          aria-label={typeof label === "string" ? label : undefined}
          {...rest}
        />
      </div>
    );
  };

// SelectInput: the name has to go through `inputProps` to reach the real [role=combobox].
const labelledSelect = (Base: React.ComponentType<Record<string, unknown>>) =>
  function LabelledSelect({
    label,
    inputProps,
    ...rest
  }: Props & { inputProps?: Record<string, unknown> }) {
    if (!label) return <Base inputProps={inputProps} {...rest} />;
    const name = typeof label === "string" ? label : undefined;
    return (
      <div className="gitcfg-field">
        <span className="gitcfg-field-label">{label}</span>
        <Base inputProps={{ "aria-label": name, ...inputProps }} {...rest} />
      </div>
    );
  };

// TextInput: a real <label htmlFor> is the only association that survives its own render.
const labelledText = (Base: React.ComponentType<Record<string, unknown>>) =>
  function LabelledText({ label, id, ...rest }: Props & { id?: string }) {
    if (!label) return <Base id={id} {...rest} />;
    const name = typeof label === "string" ? label : undefined;
    const fieldId = id || (name && `gitcfg-field-${slug(name)}`);
    return (
      <div className="gitcfg-field">
        <label className="gitcfg-field-label" htmlFor={fieldId}>
          {label}
        </label>
        <Base id={fieldId} {...rest} />
      </div>
    );
  };

export const TextInput = labelledText(BaseTextInput);
export const SelectInput = labelledSelect(BaseSelectInput);
export const TextArea = labelled(BaseTextArea);
