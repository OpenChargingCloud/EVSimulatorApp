// @ts-check

/**
 * The only file that touches the document, and the only one that may.
 *
 * ## There is no `innerHTML` here, and that is enforced
 *
 * Everything this renders came from a QR code or from a station: a scanned image anyone can tape
 * over a display, and a peer on a network. A renderer that assembled HTML out of either would be one
 * forgotten escape away from running whatever they said — and the escape that gets forgotten is
 * never the one anybody was thinking about.
 *
 * So the dangerous API is simply not used. Text goes in through `textContent`, structure is built
 * from elements, and `dom.test.js` reads this file and fails if `innerHTML`, `outerHTML`,
 * `insertAdjacentHTML`, `document.write` or `eval` appear in it. That is a weaker guarantee than a
 * type system and a much stronger one than a convention, because it cannot be satisfied by being
 * careful.
 *
 * @module
 */

/**
 * @param {string} tag
 * @param {string} [className]
 * @param {string} [text]
 */
export function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className !== undefined) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

/**
 * @param {Element} parent
 * @param {...(Node | null)} children
 */
export function put(parent, ...children) {
    for (const child of children) if (child !== null) parent.appendChild(child);
    return parent;
}

/** @param {Element} node */
export function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
    return node;
}

/**
 * A label/value pair, the shape most of this application's screens are made of.
 *
 * @param {string} label
 * @param {string} value
 * @param {string} [tone]
 */
export function field(label, value, tone) {
    return put(el("div", tone === undefined ? "field" : `field ${tone}`),
               el("span", "label", label),
               el("span", "value", value));
}

/**
 * @param {string} text
 * @param {() => void} onClick
 * @param {{primary?: boolean, disabled?: boolean}} [options]
 */
export function button(text, onClick, options) {
    const node = /** @type {HTMLButtonElement} */ (el("button", options?.primary ? "primary" : "", text));
    node.type = "button";
    node.disabled = options?.disabled === true;
    node.addEventListener("click", onClick);
    return node;
}

/**
 * A choice between a few named values.
 *
 * @template {string} T
 * @param {string} label
 * @param {readonly {value: T, text: string}[]} options
 * @param {T} selected
 * @param {(value: T) => void} onChange
 */
export function choice(label, options, selected, onChange) {

    const select = /** @type {HTMLSelectElement} */ (el("select"));

    for (const option of options) {
        const node = /** @type {HTMLOptionElement} */ (el("option", undefined, option.text));
        node.value = option.value;
        node.selected = option.value === selected;
        select.appendChild(node);
    }

    select.addEventListener("change", () => onChange(/** @type {T} */ (select.value)));

    return put(el("label", "choice"), el("span", "label", label), select);
}

/**
 * A text field, returned together with a way to read it.
 *
 * **No `input` listener, and no mirrored variable.** A screen that copied the value into a local on
 * every `input` event has two states that can disagree, and they do: a paste, an autofill or an
 * Android IME composition can set a field without firing the event the mirror depends on, and then
 * the button acts on what the user typed *before*. Found exactly that way — the sheet refused a
 * pasted code that was plainly in the box.
 *
 * So the field is the state, and callers read it when they need it.
 *
 * @param {string} label
 * @param {string} value
 * @param {{placeholder?: string, inputMode?: string}} [options]
 * @returns {{row: HTMLElement, read: () => string}}
 */
export function textInput(label, value, options) {

    const input = /** @type {HTMLInputElement} */ (el("input"));
    input.type = "text";
    input.value = value;
    if (options?.placeholder !== undefined) input.placeholder = options.placeholder;
    if (options?.inputMode !== undefined) input.inputMode = options.inputMode;

    const row = /** @type {HTMLElement} */ (
        put(el("label", "choice"), el("span", "label", label), input));

    return { row, read: () => input.value };
}

/** @param {string} text */
export function monospace(text) {
    return el("pre", "mono", text);
}
