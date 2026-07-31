/**
 * The JSON-LD form's value types.
 *
 * ## Why there is no hand-written JSON tree here
 *
 * The Kotlin and Swift back ends each carry one, because the agreement between the four back ends is
 * checked as **text** and both languages would otherwise have surrendered member order to a
 * `LinkedHashMap`-that-is-not or an unordered `Dictionary`, and escaping and number formatting to a
 * library's conventions.
 *
 * TypeScript needs none of that. A JavaScript object preserves the insertion order of its string
 * keys by specification, `JSON.stringify` walks them in that order, and its escaping and number
 * formatting are fixed by the same specification rather than by a library. So the plain object *is*
 * the ordered tree, and `JSON.stringify` *is* the writer — which is the one place in this port where
 * the language does more of the work than the others, not less.
 */
export type JsonValue = string | number | boolean | null | JsonValue[] | JsonObject;

export interface JsonObject {
    [key: string]: JsonValue | undefined;
}
