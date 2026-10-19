// Function reference for the workflow expression language.
//
// Generated from the Java registry (core/workflow/.../expression/BuiltIns.java):
// name, category, signature, doc, example and exampleResult are copied verbatim
// from BuiltIn, and BuiltInsTest already asserts that every example really
// evaluates to its exampleResult. functionReference.test.ts fails if a function
// is added, removed or renamed in BuiltIns.java without this file following, so
// the public docs cannot drift from the engine. "now" is shown with the fixed
// instant the registry test pins; the real function returns the current time.

export interface FunctionDoc {
  name: string;
  category: string;
  signature: string;
  doc: string;
  example: string;
  /** Compact JSON the example evaluates to. */
  result: string;
}

export const FUNCTION_DOCS: readonly FunctionDoc[] = [
  {"name":"lower","category":"String","signature":"lower(text)","doc":"Converts text to lower case.","example":"lower('HeLLo')","result":"\"hello\""},
  {"name":"upper","category":"String","signature":"upper(text)","doc":"Converts text to upper case.","example":"upper('abc')","result":"\"ABC\""},
  {"name":"trim","category":"String","signature":"trim(text)","doc":"Removes leading and trailing whitespace.","example":"trim('  hi  ')","result":"\"hi\""},
  {"name":"split","category":"String","signature":"split(text, separator)","doc":"Splits text into an array on a literal separator. An empty separator splits into characters.","example":"split('a,b,c', ',')","result":"[\"a\",\"b\",\"c\"]"},
  {"name":"join","category":"String","signature":"join(array, separator = \",\")","doc":"Joins array items into one string; null items become empty text.","example":"join(['a','b'], '-')","result":"\"a-b\""},
  {"name":"replace","category":"String","signature":"replace(text, search, replacement)","doc":"Replaces every occurrence of a literal substring.","example":"replace('a-b-c', '-', '+')","result":"\"a+b+c\""},
  {"name":"regexReplace","category":"String","signature":"regexReplace(text, pattern, replacement, flags = \"\")","doc":"Replaces every regex match. The replacement may reference numbered capture groups; flags are i, m, s. Patterns are length-capped and time-limited.","example":"regexReplace('a1b22', '[0-9]+', '#')","result":"\"a#b#\""},
  {"name":"substring","category":"String","signature":"substring(text, start, end = length)","doc":"Returns the part of text between two zero-based positions (clamped to the text).","example":"substring('abcdef', 1, 4)","result":"\"bcd\""},
  {"name":"contains","category":"String","signature":"contains(textOrArrayOrObject, value)","doc":"True if the text contains the substring, the array contains the value, or the object has the key.","example":"contains('hello', 'ell')","result":"true"},
  {"name":"startsWith","category":"String","signature":"startsWith(text, prefix)","doc":"True if the text starts with the prefix.","example":"startsWith('hello', 'he')","result":"true"},
  {"name":"endsWith","category":"String","signature":"endsWith(text, suffix)","doc":"True if the text ends with the suffix.","example":"endsWith('hello', 'lo')","result":"true"},
  {"name":"length","category":"String","signature":"length(textOrArrayOrObject)","doc":"Number of characters, array items or object keys; 0 for null.","example":"length([1, 2, 3])","result":"3"},
  {"name":"padStart","category":"String","signature":"padStart(text, length, pad = \" \")","doc":"Left-pads text to the given length by repeating the pad text.","example":"padStart('7', 3, '0')","result":"\"007\""},
  {"name":"urlEncode","category":"String","signature":"urlEncode(text)","doc":"Percent-encodes text for use in a URL component.","example":"urlEncode('a b&c')","result":"\"a%20b%26c\""},
  {"name":"base64","category":"String","signature":"base64(text)","doc":"Base64-encodes the UTF-8 bytes of the text.","example":"base64('hi')","result":"\"aGk=\""},
  {"name":"base64Decode","category":"String","signature":"base64Decode(text)","doc":"Decodes base64 text to a UTF-8 string.","example":"base64Decode('aGk=')","result":"\"hi\""},
  {"name":"round","category":"Number","signature":"round(number, digits = 0)","doc":"Rounds to a number of decimal places (halves round away from zero).","example":"round(2.567, 2)","result":"2.57"},
  {"name":"floor","category":"Number","signature":"floor(number)","doc":"Rounds down to the nearest integer.","example":"floor(2.9)","result":"2"},
  {"name":"ceil","category":"Number","signature":"ceil(number)","doc":"Rounds up to the nearest integer.","example":"ceil(2.1)","result":"3"},
  {"name":"abs","category":"Number","signature":"abs(number)","doc":"Absolute value.","example":"abs(-5)","result":"5"},
  {"name":"min","category":"Number","signature":"min(a, b, ...) or min(array)","doc":"Smallest of the given numbers or of one array.","example":"min(3, 1, 2)","result":"1"},
  {"name":"max","category":"Number","signature":"max(a, b, ...) or max(array)","doc":"Largest of the given numbers or of one array.","example":"max(3, 1, 2)","result":"3"},
  {"name":"sum","category":"Number","signature":"sum(array, fn = x => x)","doc":"Adds up an array of numbers (null items are skipped); an optional function picks the number from each item.","example":"sum([1, 2, 3])","result":"6"},
  {"name":"avg","category":"Number","signature":"avg(array, fn = x => x)","doc":"Average of an array of numbers (null items are skipped); null for an empty array.","example":"avg([1, 2, 3])","result":"2"},
  {"name":"toNumber","category":"Number","signature":"toNumber(value)","doc":"Converts a number or numeric text to a number; null if it is not numeric.","example":"toNumber('42')","result":"42"},
  {"name":"toFixed","category":"Number","signature":"toFixed(number, digits)","doc":"Formats a number with a fixed number of decimals, as text.","example":"toFixed(3.14159, 2)","result":"\"3.14\""},
  {"name":"map","category":"Array","signature":"map(array, fn)","doc":"Transforms every item with a function.","example":"map([1, 2, 3], x => x * 2)","result":"[2,4,6]"},
  {"name":"filter","category":"Array","signature":"filter(array, fn)","doc":"Keeps the items for which the function is truthy.","example":"filter([1, 2, 3, 4], x => x > 2)","result":"[3,4]"},
  {"name":"find","category":"Array","signature":"find(array, fn)","doc":"First item for which the function is truthy, or null.","example":"find([1, 2, 3], x => x > 1)","result":"2"},
  {"name":"some","category":"Array","signature":"some(array, fn)","doc":"True if the function is truthy for at least one item.","example":"some([1, 2, 3], x => x > 2)","result":"true"},
  {"name":"every","category":"Array","signature":"every(array, fn)","doc":"True if the function is truthy for every item (true for an empty array).","example":"every([1, 2, 3], x => x > 0)","result":"true"},
  {"name":"first","category":"Array","signature":"first(array)","doc":"First item, or null for an empty array.","example":"first([7, 8])","result":"7"},
  {"name":"last","category":"Array","signature":"last(array)","doc":"Last item, or null for an empty array.","example":"last([7, 8])","result":"8"},
  {"name":"slice","category":"Array","signature":"slice(arrayOrText, start, end = length)","doc":"Part of an array or text between two positions; negative positions count from the end.","example":"slice([1, 2, 3, 4], 1, 3)","result":"[2,3]"},
  {"name":"sort","category":"Array","signature":"sort(array, keyFn or \"asc\"/\"desc\", order = \"asc\")","doc":"Sorts a copy of the array (stable). Give a function to sort by a derived key; nulls sort last.","example":"sort([3, 1, 2])","result":"[1,2,3]"},
  {"name":"unique","category":"Array","signature":"unique(array)","doc":"Removes duplicate items, keeping the first of each.","example":"unique([1, 2, 2, 3])","result":"[1,2,3]"},
  {"name":"flatten","category":"Array","signature":"flatten(array, depth = 1)","doc":"Flattens nested arrays by the given depth.","example":"flatten([[1], [2, [3]]])","result":"[1,2,[3]]"},
  {"name":"reverse","category":"Array","signature":"reverse(arrayOrText)","doc":"Reverses an array or text (returns a copy).","example":"reverse([1, 2, 3])","result":"[3,2,1]"},
  {"name":"count","category":"Array","signature":"count(array, fn)","doc":"Number of items, or of items for which the function is truthy.","example":"count([1, 2, 3, 4], x => x % 2 == 0)","result":"2"},
  {"name":"pluck","category":"Array","signature":"pluck(array, path)","doc":"Reads a property path (like \"a.b[0]\") from every item.","example":"pluck([{a: 1}, {a: 2}], 'a')","result":"[1,2]"},
  {"name":"keys","category":"Object","signature":"keys(object)","doc":"The object's keys as an array.","example":"keys({a: 1, b: 2})","result":"[\"a\",\"b\"]"},
  {"name":"values","category":"Object","signature":"values(object)","doc":"The object's values as an array.","example":"values({a: 1, b: 2})","result":"[1,2]"},
  {"name":"entries","category":"Object","signature":"entries(object)","doc":"The object as an array of [key, value] pairs.","example":"entries({a: 1})","result":"[[\"a\",1]]"},
  {"name":"merge","category":"Object","signature":"merge(a, b, ...)","doc":"Shallow-merges objects left to right; later keys win.","example":"merge({a: 1}, {b: 2})","result":"{\"a\":1,\"b\":2}"},
  {"name":"pick","category":"Object","signature":"pick(object, key, ...)","doc":"A copy of the object with only the listed keys (keys may also be one array).","example":"pick({a: 1, b: 2}, 'a')","result":"{\"a\":1}"},
  {"name":"omit","category":"Object","signature":"omit(object, key, ...)","doc":"A copy of the object without the listed keys (keys may also be one array).","example":"omit({a: 1, b: 2}, 'a')","result":"{\"b\":2}"},
  {"name":"get","category":"Object","signature":"get(value, path, default = null)","doc":"Reads a property path like \"a.b[0].c\" (negative indexes count from the end); returns the default when missing.","example":"get({a: [{b: 5}]}, 'a[0].b', 0)","result":"5"},
  {"name":"now","category":"Date","signature":"now()","doc":"The current instant as an ISO-8601 UTC string (same value as $now).","example":"now()","result":"\"2026-09-30T10:00:00Z\""},
  {"name":"formatDate","category":"Date","signature":"formatDate(date, pattern = ISO, zone = \"UTC\")","doc":"Formats an ISO-8601 date or epoch-millis number with a java.time pattern (yyyy-MM-dd HH:mm) in an IANA zone.","example":"formatDate('2026-09-30T10:00:00Z', 'yyyy-MM-dd')","result":"\"2026-09-30\""},
  {"name":"parseDate","category":"Date","signature":"parseDate(text, pattern = ISO, zone = \"UTC\")","doc":"Parses text (ISO-8601, or a java.time pattern) into an ISO-8601 UTC string.","example":"parseDate('30/09/2026', 'dd/MM/yyyy')","result":"\"2026-09-30T00:00:00Z\""},
  {"name":"addDays","category":"Date","signature":"addDays(date, days, zone = \"UTC\")","doc":"Adds (or subtracts, if negative) whole days, calendar-aware in the given zone.","example":"addDays('2026-09-30T00:00:00Z', 2)","result":"\"2026-10-02T00:00:00Z\""},
  {"name":"addHours","category":"Date","signature":"addHours(date, hours)","doc":"Adds (or subtracts) whole hours.","example":"addHours('2026-09-30T00:00:00Z', 5)","result":"\"2026-09-30T05:00:00Z\""},
  {"name":"diffDays","category":"Date","signature":"diffDays(from, to, zone = \"UTC\")","doc":"Whole days from the first date to the second (negative if it is earlier).","example":"diffDays('2026-09-01T00:00:00Z', '2026-09-11T00:00:00Z')","result":"10"},
  {"name":"toEpoch","category":"Date","signature":"toEpoch(date)","doc":"Milliseconds since 1970-01-01T00:00:00Z.","example":"toEpoch('1970-01-01T00:00:01Z')","result":"1000"},
  {"name":"toString","category":"Type","signature":"toString(value)","doc":"Converts a value to text (null becomes empty text, objects and arrays become JSON).","example":"toString(12)","result":"\"12\""},
  {"name":"toBoolean","category":"Type","signature":"toBoolean(value)","doc":"Converts to true/false: \"true\"/\"yes\"/\"1\" are true, \"false\"/\"no\"/\"0\"/empty are false; numbers are true unless 0.","example":"toBoolean('true')","result":"true"},
  {"name":"parseJson","category":"Type","signature":"parseJson(text)","doc":"Parses JSON text into a value; an error if it is not valid JSON.","example":"parseJson('{\"a\":1}')","result":"{\"a\":1}"},
  {"name":"toJson","category":"Type","signature":"toJson(value)","doc":"Serialises a value to compact JSON text.","example":"toJson({a: 1})","result":"\"{\\\"a\\\":1}\""},
  {"name":"typeOf","category":"Type","signature":"typeOf(value)","doc":"One of null, boolean, number, string, array, object.","example":"typeOf([1])","result":"\"array\""},
  {"name":"isEmpty","category":"Type","signature":"isEmpty(value)","doc":"True for null, empty text, empty arrays and empty objects.","example":"isEmpty('')","result":"true"},
  {"name":"isNumber","category":"Type","signature":"isNumber(value)","doc":"True for numbers and numeric text.","example":"isNumber('12')","result":"true"},
  {"name":"if","category":"Logic","signature":"if(condition, then, else)","doc":"Evaluates only the branch that is chosen.","example":"if(1 > 0, 'yes', 'no')","result":"\"yes\""},
  {"name":"coalesce","category":"Logic","signature":"coalesce(a, b, ...)","doc":"The first argument that is not null; later ones are not evaluated.","example":"coalesce(null, 'fallback')","result":"\"fallback\""},
];

/** Categories in registry order, each with its functions. */
export function functionsByCategory(): { category: string; functions: FunctionDoc[] }[] {
  const order: string[] = [];
  const map = new Map<string, FunctionDoc[]>();
  for (const f of FUNCTION_DOCS) {
    if (!map.has(f.category)) {
      map.set(f.category, []);
      order.push(f.category);
    }
    map.get(f.category)!.push(f);
  }
  return order.map((category) => ({ category, functions: map.get(category)! }));
}
