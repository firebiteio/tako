/**
 * The page's texts from the IDE's TakoPageBundle. Arguments use Java's MessageFormat syntax, so one bundle reads the
 * same to translators everywhere: {0} inserts an argument, {0,choice,0#no files|1#one file|1<{0} files} picks by
 * number, and with arguments a literal apostrophe is written ''.
 */

declare global {
  interface Window {
    takoStrings: Record<string, string>;
  }
}

/** The text of a key, or the key itself when the bundle lacks it. Keys are literal so tests can find them. */
export function t(key: string, ...args: (string | number)[]): string {
  const text = window.takoStrings[key] ?? key;
  return args.length === 0 ? text : format(text, args);
}

function format(pattern: string, args: (string | number)[]): string {
  let out = '';
  let i = 0;
  while (i < pattern.length) {
    const c = pattern[i];
    if (c === "'") {
      if (pattern[i + 1] === "'") {
        out += "'";
        i += 2;
        continue;
      }
      const end = pattern.indexOf("'", i + 1);
      const close = end < 0 ? pattern.length : end;
      out += pattern.slice(i + 1, close);
      i = close + 1;
      continue;
    }
    if (c === '{') {
      const close = matchingBrace(pattern, i);
      out += argument(pattern.slice(i + 1, close), args);
      i = close + 1;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

function matchingBrace(pattern: string, open: number): number {
  let depth = 0;
  for (let i = open; i < pattern.length; i++) {
    if (pattern[i] === '{') {
      depth++;
    } else if (pattern[i] === '}' && --depth === 0) {
      return i;
    }
  }
  return pattern.length;
}

function argument(spec: string, args: (string | number)[]): string {
  const comma = spec.indexOf(',');
  const value = args[Number(comma < 0 ? spec : spec.slice(0, comma))];
  if (comma < 0) {
    return String(value ?? '');
  }
  const choices = spec.slice(spec.indexOf(',', comma + 1) + 1);
  return format(choose(choices, Number(value)), args);
}

/** The text of the last choice whose limit the number reaches: 1#… means from 1 on, 1<… means above 1. */
function choose(choices: string, n: number): string {
  let picked: string | null = null;
  for (const choice of splitChoices(choices)) {
    const mark = choice.search(/[#<]/);
    const limit = Number(choice.slice(0, mark));
    const reached = choice[mark] === '#' ? n >= limit : n > limit;
    if (reached || picked === null) {
      picked = choice.slice(mark + 1);
    }
  }
  return picked ?? '';
}

function splitChoices(choices: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < choices.length; i++) {
    if (choices[i] === '{') {
      depth++;
    } else if (choices[i] === '}') {
      depth--;
    } else if (choices[i] === '|' && depth === 0) {
      parts.push(choices.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(choices.slice(start));
  return parts;
}
