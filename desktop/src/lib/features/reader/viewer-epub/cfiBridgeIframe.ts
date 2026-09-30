/**
 * Plain-JS string of the CFI bridge, inlined into the EPUB iframe's
 * `srcdoc`. Mirrors `cfiBridge.ts` so the same code runs in the
 * iframe (where there is no module system) and in the parent / tests
 * (where TypeScript imports are used).
 *
 * Why a duplicate, not `?raw` import of `cfiBridge.ts`:
 *   - `?raw` returns the TypeScript source verbatim, including type
 *     annotations and `interface` declarations, which a browser cannot
 *     parse.
 *   - Transpiling `.ts` to `.js` at build time would require an extra
 *     Vite plugin or a build script.
 *   - The bridge is small (~100 lines). Maintaining a parallel plain-JS
 *     string is cheaper than the build-tooling alternative.
 *
 * The two implementations are kept in sync by the unit tests in
 * `cfiBridge.test.ts`, which exercise the same DOM operations and
 * assert the same outputs. If the bridge logic changes, update both
 * files and run the tests.
 *
 * Exposed to the iframe as `window.__cfiBridge` (an object with
 * `rangeToCFI`, `cfiToRange`, `getChapterBaseCFI`, `setSpine`). The
 * parent calls `__cfiBridge.setSpine(spineHrefs)` at iframe init.
 */
export const IFRAME_CFI_BRIDGE_SCRIPT = `
(function() {
  if (window.__cfiBridge) return; // idempotent
  var BLACKLIST_TAGS = { audio: 1, video: 1, script: 1, link: 1, style: 1, object: 1, embed: 1 };
  function isBlacklisted(node) {
    if (node.nodeType !== 1) return false;
    var el = node;
    if (el.id === 'nexo-reader-overrides') return true;
    return !!BLACKLIST_TAGS[el.tagName.toLowerCase()];
  }
  function cfiChildIndex(parent, target) {
    var children = parent.childNodes;
    var elementIndex = 0;
    var textIndex = 0;
    var found = false;
    var result = 0;
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (isBlacklisted(child)) continue;
      if (child === target) {
        if (child.nodeType === 3) {
          result = 2 * textIndex + 1;
        } else {
          result = 2 * (elementIndex + 1);
        }
        found = true;
        break;
      }
      if (child.nodeType === 3) {
        textIndex += 1;
      } else {
        elementIndex += 1;
      }
    }
    return found ? result : null;
  }
  var spineHrefs = [];
  function normalizeHref(href) { return typeof href === 'string' ? href.replace(/\\\\/g, '/') : href; }
  function setSpine(hrefs) {
    if (!hrefs || !hrefs.length) { spineHrefs = []; return; }
    spineHrefs = hrefs.map(function(h){ return normalizeHref(h); });
  }
  function getSpineIndex(chapterHref) {
    if (typeof chapterHref !== 'string' || chapterHref.length === 0) return null;
    var normalized = normalizeHref(chapterHref);
    var idx = spineHrefs.indexOf(normalized);
    if (idx < 0) return null;
    return idx + 1;
  }
  function getChapterBaseCFI(chapterHref) {
    var idx = getSpineIndex(chapterHref);
    if (idx === null) return null;
    return 'epubcfi(/6/' + idx + '!)';
  }
  function getChapterBasePrefix(chapterHref) {
    var idx = getSpineIndex(chapterHref);
    if (idx === null) return null;
    return 'epubcfi(/6/' + idx + '!';
  }
  function buildLocalPath(startNode, doc) {
    var current = startNode;
    if (current.nodeType !== 1) current = current.parentNode;
    if (!current || current.nodeType !== 1) return null;
    var docElement = doc.documentElement;
    if (!docElement) return null;
    var segments = [];
    var node = current;
    while (node && node !== docElement) {
      if (node.nodeType === 1) {
        var parent = node.parentNode;
        if (!parent) return null;
        var idx = cfiChildIndex(parent, node);
        if (idx === null) return null;
        segments.unshift(idx);
      }
      node = node.parentNode;
    }
    if (node !== docElement) return null;
    if (segments.length === 0) return null;
    return '/' + segments.join('/');
  }
  // LOCKSTEP: see cfiBridge.test.ts "cross-paragraph round-trip preserves endpoints".
  // This helper mirrors findFirstTextDescendant in cfiBridge.ts. It is added
  // alongside the Bug B element-container fix; keep both files in sync.
  function findFirstTextDescendant(node) {
    if (node.nodeType === 3) return node;
    var children = node.childNodes;
    for (var i = 0; i < children.length; i++) {
      var c = children[i];
      if (isBlacklisted(c)) continue;
      var found = findFirstTextDescendant(c);
      if (found) return found;
    }
    return null;
  }
  // LOCKSTEP: see cfiBridge.test.ts "cross-paragraph round-trip preserves endpoints".
  // textTerminusStep in cfiBridgeIframe.ts must stay byte-for-byte equivalent
  // (modulo types) to textTerminusStep in cfiBridge.ts.
  function textTerminusStep(container, offset, commonAncestor) {
    var textNode = null;
    var forceTextEnd = false;
    if (container.nodeType === 3) {
      textNode = container;
    } else if (container.nodeType === 1) {
      var children = container.childNodes;
      if (children.length === 0) return null;
      var targetChild;
      if (offset <= 0) {
        targetChild = children[0];
      } else if (offset >= children.length) {
        targetChild = children[children.length - 1];
        forceTextEnd = true;
      } else {
        targetChild = children[offset];
      }
      textNode = findFirstTextDescendant(targetChild);
    } else {
      return null;
    }
    if (!textNode || !textNode.parentNode || textNode.nodeType !== 3) return null;
    var desc = textNode;
    var inAncestor = false;
    while (desc) {
      if (desc === commonAncestor) { inAncestor = true; break; }
      desc = desc.parentNode;
    }
    if (!inAncestor) return null;
    var segments = [];
    var cur = textNode.parentNode;
    while (cur && cur !== commonAncestor) {
      var p = cur.parentNode;
      if (!p) return null;
      var idx = cfiChildIndex(p, cur);
      if (idx === null) return null;
      segments.unshift(idx);
      cur = p;
    }
    if (cur !== commonAncestor) return null;
    var textIdx = cfiChildIndex(textNode.parentNode, textNode);
    if (textIdx === null) return null;
    segments.push(textIdx);
    var totalLen = (textNode.nodeValue || '').length;
    var clampedOffset = forceTextEnd ? totalLen : Math.max(0, Math.min(offset, totalLen));
    return '/' + segments.join('/') + ':' + clampedOffset;
  }
  function rangeToCFI(range, chapterHref, doc) {
    try {
      if (!range || !doc || typeof chapterHref !== 'string' || chapterHref.length === 0) return null;
      var base = getChapterBasePrefix(chapterHref);
      if (!base) { console.warn('epub-cfi: chapter not in spine registry', chapterHref); return null; }
      var rawAncestor = range.commonAncestorContainer;
      var commonAncestor = rawAncestor;
      if (commonAncestor.nodeType === 3) {
        if (!commonAncestor.parentNode) return null;
        commonAncestor = commonAncestor.parentNode;
      }
      if (commonAncestor.nodeType !== 1) return null;
      var localPath = buildLocalPath(commonAncestor, doc);
      if (!localPath) {
        // Diagnose WHY buildLocalPath failed: is the common ancestor an
        // element with no indexable path to <html>, or is it the html/body
        // root itself? This surfaces the exact failing structure.
        var diag = (function () {
          var el = commonAncestor;
          var depth = 0;
          while (el && el.nodeType === 1 && el !== doc.documentElement && depth < 20) {
            el = el.parentNode; depth++;
          }
          return {
            ancestorTag: (commonAncestor && commonAncestor.tagName) ? commonAncestor.tagName.toLowerCase() : String(commonAncestor),
            ancestorId: (commonAncestor && commonAncestor.id) ? commonAncestor.id : '',
            ancestorIsRoot: commonAncestor === doc.documentElement,
            ancestorIsBody: commonAncestor === doc.body,
            depthToRoot: depth,
            startTag: (range.startContainer && range.startContainer.nodeType === 1) ? range.startContainer.tagName.toLowerCase() : 'text',
            startParentTag: (range.startContainer && range.startContainer.parentNode && range.startContainer.parentNode.nodeType === 1) ? range.startContainer.parentNode.tagName.toLowerCase() : 'none',
            startOffset: range.startOffset
          };
        })();
        console.warn('epub-cfi: failed to build local path', chapterHref, diag);
        return null;
      }
      var startTerminus = textTerminusStep(range.startContainer, range.startOffset, commonAncestor);
      if (startTerminus === null) { console.warn('epub-cfi: failed to compute start terminus'); return null; }
      var endTerminus = textTerminusStep(range.endContainer, range.endOffset, commonAncestor);
      if (endTerminus === null) { console.warn('epub-cfi: failed to compute end terminus'); return null; }
      return base + localPath + ',' + startTerminus + ',' + endTerminus + ')';
    } catch (err) {
      console.warn('epub-cfi: rangeToCFI failed', err);
      return null;
    }
  }
  function findChildByCfiIndex(parent, cfiIndex) {
    var children = parent.childNodes;
    var elementIndex = 0;
    var textIndex = 0;
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (isBlacklisted(child)) continue;
      if (cfiIndex % 2 === 1) {
        if (child.nodeType === 3) {
          if (2 * textIndex + 1 === cfiIndex) return child;
          textIndex += 1;
        }
      } else {
        if (child.nodeType === 1) {
          if (2 * (elementIndex + 1) === cfiIndex) return child;
          elementIndex += 1;
        }
      }
    }
    return null;
  }
  function resolveTextTerminus(commonAncestor, chain, offset) {
    if (!chain || chain.length === 0) return null;
    var lastStep = chain[chain.length - 1];
    if (!lastStep || lastStep % 2 !== 1) return null;
    var current = commonAncestor;
    for (var i = 0; i < chain.length - 1; i++) {
      var step = chain[i];
      var target = findChildByCfiIndex(current, step);
      if (!target || target.nodeType !== 1) return null;
      current = target;
    }
    var textNode = findChildByCfiIndex(current, lastStep);
    if (!textNode || textNode.nodeType !== 3) return null;
    var text = textNode.nodeValue || '';
    var clamped = Math.max(0, Math.min(offset, text.length));
    return { node: textNode, offset: clamped };
  }
  function cfiToRange(cfi, chapterHref, doc) {
    try {
      if (!cfi || !doc || typeof chapterHref !== 'string' || chapterHref.length === 0) return null;
      var m = /^epubcfi\\(\\/6\\/(\\d+)!(.+)\\)$/.exec(cfi);
      if (!m || !m[1] || !m[2]) { console.warn('epub-cfi: failed to parse cfi', cfi); return null; }
      var spineIndex = parseInt(m[1], 10);
      if (!isFinite(spineIndex) || spineIndex <= 0) return null;
      var registeredIdx = getSpineIndex(chapterHref);
      if (registeredIdx === null || registeredIdx !== spineIndex) {
        console.warn('epub-cfi: spine index mismatch');
        return null;
      }
      var tail = m[2];
      var parts = tail.split(',');
      if (parts.length < 3) return null;
      var localPathStr = parts[0] || '';
      var startStr = parts[1] || '';
      var endStr = parts[2] || '';
      var localPath = localPathStr.split('/').filter(function (s) { return s.length > 0; })
        .map(function (s) { return parseInt(s, 10); })
        .filter(function (n) { return isFinite(n) && n > 0; });
      function parseTerminusChain(s) {
        if (typeof s !== 'string' || s.length === 0) return null;
        var segs = s.split('/').filter(function (x) { return x.length > 0; });
        if (segs.length === 0) return null;
        var last = segs[segs.length - 1] || '';
        var tm = /^(\\d+):(\\d+)$/.exec(last);
        if (!tm || !tm[1] || !tm[2]) return null;
        var lastStep = parseInt(tm[1], 10);
        var off = parseInt(tm[2], 10);
        if (!isFinite(lastStep) || !isFinite(off) || lastStep <= 0 || lastStep % 2 !== 1 || off < 0) return null;
        var chain = [];
        for (var k = 0; k < segs.length - 1; k++) {
          var n = parseInt(segs[k], 10);
          if (!isFinite(n) || n <= 0 || n % 2 !== 0) return null;
          chain.push(n);
        }
        chain.push(lastStep);
        return { chain: chain, offset: off };
      }
      var start = parseTerminusChain(startStr);
      var end = parseTerminusChain(endStr);
      if (!start || !end) return null;
      var root = doc.documentElement;
      if (!root) return null;
      var commonAncestor = root;
      for (var j = 0; j < localPath.length; j++) {
        var t = findChildByCfiIndex(commonAncestor, localPath[j]);
        if (!t || t.nodeType !== 1) { console.warn('epub-cfi: local path did not resolve'); return null; }
        commonAncestor = t;
      }
      var startText = resolveTextTerminus(commonAncestor, start.chain, start.offset);
      var endText = resolveTextTerminus(commonAncestor, end.chain, end.offset);
      if (!startText || !endText) { console.warn('epub-cfi: text terminus did not resolve'); return null; }
      var range = doc.createRange();
      range.setStart(startText.node, startText.offset);
      range.setEnd(endText.node, endText.offset);
      return range;
    } catch (err) {
      console.warn('epub-cfi: cfiToRange failed', err);
      return null;
    }
  }
  window.__cfiBridge = {
    rangeToCFI: rangeToCFI,
    cfiToRange: cfiToRange,
    getChapterBaseCFI: getChapterBaseCFI,
    setSpine: setSpine
  };
})();
`;
