/**
 * Custom ESLint rule: legacy-type-size (WARN)
 *
 * Flags the pre-scale type sizes `text-2sm` / `text-2xs`: excluded from the
 * ratified scale but still load-bearing until UI-07 retires them, so they
 * warn instead of erroring. Warn never fails the build; the ~27 files still
 * on these sizes are the intended signal, not breakage.
 *
 * UI-07 FLIP: change the `local-rules/legacy-type-size` severity entry in
 * `eslint.config.js` from `'warn'` to `'error'` once the sizes are retired.
 */

const LEGACY_SIZE_PATTERN = /\btext-(2sm|2xs)\b/g;

/** @type {import('eslint').Rule.RuleModule} */
export default {
  meta: {
    type: 'suggestion',
    docs: {
      description:
        'Warn on legacy text-2sm / text-2xs sizes (excluded from the ratified scale; UI-07 retires them)',
    },
    messages: {
      legacyTypeSize:
        "Legacy size '{{actual}}' is excluded from the ratified type scale and will be retired in UI-07. Use a ratified role instead.",
    },
    schema: [],
  },
  create(context) {
    const sourceCode = context.sourceCode ?? context.getSourceCode();
    const text = sourceCode.getText();

    /** @type {Array<{ start: number; end: number; actual: string }>} */
    const reports = [];

    let match;
    while ((match = LEGACY_SIZE_PATTERN.exec(text)) !== null) {
      reports.push({
        start: match.index,
        end: match.index + match[0].length,
        actual: match[0],
      });
    }

    if (reports.length === 0) return {};

    return {
      Program(node) {
        for (const report of reports) {
          context.report({
            node,
            loc: sourceCode.getLocFromIndex(report.start),
            messageId: 'legacyTypeSize',
            data: { actual: report.actual },
          });
        }
      },
    };
  },
};
