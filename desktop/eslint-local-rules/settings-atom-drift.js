/**
 * Custom ESLint rule: settings-atom-drift (ERROR)
 *
 * Guards the settings feature tree against reintroducing what UI-01..UI-05
 * removed: raw `<button>` elements (use the Button atom) and `text-lg`, the
 * Reading serif role, in UI chrome (use the Title role `text-xl`).
 *
 * Scope is enforced twice: this rule self-gates on the settings feature path
 * AND `eslint.config.js` only enables it for
 * `src/lib/features/settings/**` Svelte files.
 *
 * NOTE (recorded, not enforced): there is deliberately NO rule here requiring
 * a `danger-outline` Button variant or `text-danger` class. Two shipped units
 * recorded that hole (the device-remove trigger and the clear-cache arm both
 * downgraded quietly because no such variant exists and a class override
 * provably loses in the cascade). A lint rule cannot require a variant that
 * does not exist: creating that variant is the precondition for any such rule,
 * and it belongs to an atom slice, not to this guard unit.
 */

const RAW_BUTTON_PATTERN = /<button(?=[\s>])/g;
const TEXT_LG_PATTERN = /\btext-lg\b/g;

const SETTINGS_TREE_PATTERN = /features[/\\]settings[/\\]/;

/**
 * @param {string} filename
 * @returns {boolean} true when the file lives inside the settings feature tree.
 */
function isSettingsTreeFile(filename) {
  return SETTINGS_TREE_PATTERN.test(filename.replace(/\\/g, '/'));
}

/** @type {import('eslint').Rule.RuleModule} */
export default {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Forbid raw <button> and text-lg inside the settings feature tree (use the Button atom and the text-xl Title role)',
    },
    fixable: 'code',
    messages: {
      rawButton:
        'Raw `<button>` is forbidden in the settings tree. Use the Button atom from `$lib/shared/ui/forms/Button.svelte`.',
      bannedTextLg:
        "The `text-lg` Reading serif role is forbidden in settings UI chrome. Use the Title role `text-xl` instead.",
    },
    schema: [],
  },
  create(context) {
    const filename = context.filename ?? context.getFilename?.() ?? '';
    if (!isSettingsTreeFile(filename)) return {};

    const sourceCode = context.sourceCode ?? context.getSourceCode();
    const text = sourceCode.getText();

    /** @type {Array<{ start: number; end: number; messageId: string; fix?: string }>} */
    const reports = [];

    let match;
    while ((match = RAW_BUTTON_PATTERN.exec(text)) !== null) {
      reports.push({
        start: match.index,
        end: match.index + match[0].length,
        messageId: 'rawButton',
      });
    }
    while ((match = TEXT_LG_PATTERN.exec(text)) !== null) {
      reports.push({
        start: match.index,
        end: match.index + match[0].length,
        messageId: 'bannedTextLg',
        fix: 'text-xl',
      });
    }

    if (reports.length === 0) return {};

    return {
      Program(node) {
        for (const report of reports) {
          context.report({
            node,
            loc: sourceCode.getLocFromIndex(report.start),
            messageId: report.messageId,
            fix:
              report.fix != null
                ? (fixer) => fixer.replaceTextRange([report.start, report.end], report.fix)
                : undefined,
          });
        }
      },
    };
  },
};
