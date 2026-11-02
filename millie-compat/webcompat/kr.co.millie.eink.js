/* webview_compat for kr.co.millie.eink (injected by framework WebCompat into android_asset HTML) */
(function(){try{var b=window.__evalBridge;if(b&&b.log)b.log("compat loaded: "+location.href+" UA="+navigator.userAgent);}catch(e){}})();
/* Android 4.2 WebView compatibility for the Millie viewer: loaded before ePub.js. */
(function () {
  function addClassList(proto) {
    if (!proto || 'classList' in proto) return;
    function get(el) { var c = el.getAttribute('class'); return c ? c.replace(/^\s+|\s+$/g, '').split(/\s+/) : []; }
    function set(el, list) { el.setAttribute('class', list.join(' ')); }
    Object.defineProperty(proto, 'classList', {
      configurable: true,
      get: function () {
        var el = this;
        return {
          add: function () { var l = get(el); for (var i = 0; i < arguments.length; i++) if (l.indexOf(arguments[i]) < 0) l.push(arguments[i]); set(el, l); },
          remove: function () { var l = get(el); for (var i = 0; i < arguments.length; i++) { var k = l.indexOf(arguments[i]); while (k >= 0) { l.splice(k, 1); k = l.indexOf(arguments[i]); } } set(el, l); },
          contains: function (c) { return get(el).indexOf(c) >= 0; },
          toggle: function (c, force) { var has = get(el).indexOf(c) >= 0; var on = force === undefined ? !has : !!force; if (on && !has) this.add(c); if (!on && has) this.remove(c); return on; },
          item: function (i) { return get(el)[i] || null; },
          get length() { return get(el).length; },
          toString: function () { return get(el).join(' '); }
        };
      }
    });
  }
  if (window.SVGElement) addClassList(SVGElement.prototype);
  if (window.Element) addClassList(Element.prototype);
  if (!window.requestAnimationFrame) {
    window.requestAnimationFrame = window.webkitRequestAnimationFrame || function (cb) { return setTimeout(function () { cb(Date.now()); }, 16); };
    window.cancelAnimationFrame = window.webkitCancelAnimationFrame || function (id) { clearTimeout(id); };
  }

  /* Old WebKit can return null from Range.getBoundingClientRect()/getClientRects() for some ranges;
     newer engines return an empty rect / empty list. */
  if (window.Range) {
    var nativeBCR = Range.prototype.getBoundingClientRect, nativeCR = Range.prototype.getClientRects;
    if (nativeBCR) Range.prototype.getBoundingClientRect = function () {
      var r = nativeBCR.apply(this, arguments);
      return r || { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0 };
    };
    if (nativeCR) Range.prototype.getClientRects = function () {
      var r = nativeCR.apply(this, arguments);
      return r || [];
    };
  }
  /* Old WebKit throws WRONG_DOCUMENT_ERR when a Range made by one document is pointed at a node of
     another document; newer engines just move the range. document.createRange() hands out a range
     that is (re)created in the document of the first node it is given. Only installed when needed. */
  (function () {
    var needed = false;
    try {
      var other = document.implementation.createHTMLDocument('');
      var text = other.createTextNode('x');
      other.body.appendChild(text);
      document.createRange().setStart(text, 0);
    } catch (e) { needed = true; }
    if (!needed || !window.Range) return;
    var nativeCreate = Document.prototype.createRange;
    function docOf(node) { return node.nodeType === 9 ? node : node.ownerDocument; }
    function unwrap(r) { return r && r.__real ? r.__real : r; }
    function LazyRange(doc) { this.__real = nativeCreate.call(doc); }
    LazyRange.prototype = Object.create(Range.prototype);
    LazyRange.prototype.constructor = LazyRange;
    function rebind(lr, node) {
      var d = docOf(node), r = lr.__real, cur = docOf(r.startContainer);
      if (!d || d === cur) return;
      var n = nativeCreate.call(d);
      lr.__real = n;
    }
    ['setStart', 'setEnd', 'setStartBefore', 'setStartAfter', 'setEndBefore', 'setEndAfter', 'selectNode', 'selectNodeContents']
      .forEach(function (name) {
        LazyRange.prototype[name] = function (node) {
          rebind(this, node);
          return this.__real[name].apply(this.__real, arguments);
        };
      });
    ['collapse', 'cloneContents', 'deleteContents', 'extractContents', 'insertNode', 'surroundContents', 'toString',
     'detach', 'getClientRects', 'getBoundingClientRect', 'isPointInRange', 'comparePoint', 'intersectsNode',
     'createContextualFragment', 'expand']
      .forEach(function (name) {
        LazyRange.prototype[name] = function () {
          var f = this.__real[name];
          return typeof f === 'function' ? f.apply(this.__real, arguments) : undefined;
        };
      });
    LazyRange.prototype.cloneRange = function () {
      var c = Object.create(LazyRange.prototype);
      c.__real = this.__real.cloneRange();
      return c;
    };
    LazyRange.prototype.compareBoundaryPoints = function (how, other) {
      return this.__real.compareBoundaryPoints(how, unwrap(other));
    };
    ['startContainer', 'startOffset', 'endContainer', 'endOffset', 'collapsed', 'commonAncestorContainer']
      .forEach(function (name) {
        Object.defineProperty(LazyRange.prototype, name, { get: function () { return this.__real[name]; } });
      });
    Document.prototype.createRange = function () { return new LazyRange(this); };
    var nativeCompare = Range.prototype.compareBoundaryPoints;
    Range.prototype.compareBoundaryPoints = function (how, other) { return nativeCompare.call(this, how, unwrap(other)); };
    if (window.Selection) {
      var nativeAdd = Selection.prototype.addRange;
      Selection.prototype.addRange = function (r) { return nativeAdd.call(this, unwrap(r)); };
    }
  })();
})();

/* ---- DOM event constructors (old WebKit: "Illegal constructor") ---- */
(function () {
  var log = function (m) { try { window.__evalBridge && window.__evalBridge.log(m); } catch (e) {} };
  function works(C, a) { try { new C(a, {}); return true; } catch (e) { return false; } }
  function opt(d, k, def) { return d && d[k] !== undefined ? d[k] : def; }
  function install(name, make) {
    var Native = window[name];
    if (Native && works(Native, 'x')) return;
    var Poly = function (type, d) { return make(String(type), d || {}); };
    if (Native && Native.prototype) Poly.prototype = Native.prototype;
    try { window[name] = Poly; } catch (e) {}
    log('polyfilled ' + name + ' ctor (native=' + !!Native + ')');
  }
  install('Event', function (t, d) {
    var e = document.createEvent('Events'); e.initEvent(t, !!opt(d, 'bubbles', false), !!opt(d, 'cancelable', false)); return e;
  });
  install('CustomEvent', function (t, d) {
    var e = document.createEvent('CustomEvent');
    if (e.initCustomEvent) e.initCustomEvent(t, !!opt(d, 'bubbles', false), !!opt(d, 'cancelable', false), opt(d, 'detail', null));
    else { e = document.createEvent('Events'); e.initEvent(t, !!opt(d, 'bubbles', false), !!opt(d, 'cancelable', false)); e.detail = opt(d, 'detail', null); }
    return e;
  });
  install('MouseEvent', function (t, d) {
    var e = document.createEvent('MouseEvents');
    e.initMouseEvent(t, !!opt(d, 'bubbles', false), !!opt(d, 'cancelable', false), opt(d, 'view', window), opt(d, 'detail', 0),
      opt(d, 'screenX', 0), opt(d, 'screenY', 0), opt(d, 'clientX', 0), opt(d, 'clientY', 0),
      !!opt(d, 'ctrlKey', false), !!opt(d, 'altKey', false), !!opt(d, 'shiftKey', false), !!opt(d, 'metaKey', false),
      opt(d, 'button', 0), opt(d, 'relatedTarget', null));
    return e;
  });
  install('KeyboardEvent', function (t, d) {
    var e = document.createEvent('Events'); e.initEvent(t, !!opt(d, 'bubbles', false), !!opt(d, 'cancelable', false));
    e.key = opt(d, 'key', ''); e.keyCode = opt(d, 'keyCode', 0); e.which = e.keyCode; return e;
  });
  /* self-test: the viewer does Object.defineProperty(evt, "target", ...) on synthetic events */
  try {
    var ev = new MouseEvent('click', { bubbles: true, clientX: 1 });
    var ok = true;
    try { Object.defineProperty(ev, 'target', { value: document.body, enumerable: true, configurable: true }); } catch (e) { ok = 'defineProperty threw: ' + e; }
    log('selftest MouseEvent ok; clientX=' + ev.clientX + ' target-override=' + ok + ' targetIsBody=' + (ev.target === document.body));
  } catch (e) { log('selftest MouseEvent failed: ' + e); }
})();

/* ---- repaint fix: old WebKit does not repaint an iframe after scrollLeft changes on an
   overflow:hidden element inside it (Millie paginates by scrolling div.epub-scrolling) ---- */
(function () {
  var log = function (m) { try { window.__evalBridge && window.__evalBridge.log(m); } catch (e) {} };
  var flip = false, count = 0;
  function nudge(f) {
    if (f.__wcPending) return;
    f.__wcPending = true;
    setTimeout(function () {
      f.__wcPending = false;
      flip = !flip;
      f.style.outline = flip ? '1px solid transparent' : '0 none transparent';
      if (++count <= 1) log('repaint nudge active');
    }, 0);
  }
  function hook(f) {
    var d;
    try { d = f.contentDocument; } catch (e) { return; }
    /* keep classic WebView from turning the iframe into a (mis-scaled) scrollable layer */
    if (f.style.overflowX !== 'hidden' || f.style.overflowY !== 'hidden') {
      f.style.overflowX = 'hidden'; f.style.overflowY = 'hidden';
      f.setAttribute('scrolling', 'no');
    }
    if (!d || f.__wcDoc === d) return;
    f.__wcDoc = d;
    d.addEventListener('scroll', function () { nudge(f); }, true);
    /* One tap produces two clicks on old WebKit: the touch library's synthetic MouseEvent and
       WebKit's own compatibility click. Both reach the viewer's onClick -> native.didTouchContentView
       -> the app turns two pages. Drop a click whose creation time and position match the previous
       one (the duplicate is created at the same moment; it is only *handled* ~1s later). */
    try {
      var w = f.contentWindow, lastTs = -1e12, lastX = null, lastY = null, dups = 0;
      w.addEventListener('click', function (e) {
        var ts = e.timeStamp || 0;
        if (Math.abs(ts - lastTs) < 400 && e.clientX === lastX && e.clientY === lastY) {
          if (e.stopImmediatePropagation) e.stopImmediatePropagation();
          e.stopPropagation();
          if (++dups <= 2) log('dropped duplicate click (dt=' + Math.round(ts - lastTs) + 'ms)');
          return;
        }
        lastTs = ts; lastX = e.clientX; lastY = e.clientY;
      }, true);
    } catch (x) { log('click dedupe hook failed: ' + x); }
  }
  setInterval(function () {
    var fs = document.getElementsByTagName('iframe');
    for (var i = 0; i < fs.length; i++) hook(fs[i]);
  }, 2000);
})();

/* ---- JS->native bridge pass-through proxy (page-turn de-dup + optional touch logging) ---- */
(function () {
  var log = function (m) { try { window.__evalBridge && window.__evalBridge.log(m); } catch (e) {} };
  var real = window.native;
  if (!real) { log('native bridge not present at compat time'); return; }
  var names = ['willStartSelection','willStartHighlight','willMoveToLocation','didUpdateEPubView','didTouchStartContentView',
    'didTouchInternalLink','didTouchHighlight','didTouchFootnote','didTouchExternalLink','didTouchEndContentView',
    'didTouchContextMenu','didTouchContentView','didSwipeRight','didSwipeLeft','didScroll','didReceiveToc',
    'didReceiveSelectionRange','didReceiveSearchResult','didReceiveRanges','didReceivePreviewText','didReceivePreloadData',
    'didReceivePageIndex','didReceiveLocation','didMoveToLocation','didMoveSelection','didMoveHighlight','didLongPressImage',
    'didLoadToLocation','didLoadEPubView','didLoadBook','didHighlightOverflowed','didFinishSelection','didEndSelection',
    'didEndHighlight','didChangedSettingMode','canMovePrevSeries','canMoveNextSeries'];
  var watch = /^(didTouch|didSwipe)/;
  var verbose = false;   /* set true to log touch-related bridge calls */
  /* Page-turn de-duplication: on this WebView one tap reaches app.goToNextPage() twice (the app's
     Java side issues it twice, and old WebKit sometimes delivers a second touch sequence). The
     duplicates arrive 10-650ms apart; an e-ink page refresh takes ~1s, so ignore a turn request
     within 1.2s of the last accepted one. */
  var lastTurn = 0, dropped = 0;
  function clearBusy() {}
  (function wrapApp() {
    var a = window.app;
    if (a && typeof a.goToNextPage === 'function' && !a.__wcTurnWrapped) {
      ['goToNextPage', 'goToPrevPage'].forEach(function (m) {
        var orig = a[m];
        a[m] = function () {
          /* de-dup moved to the framework (WebView.loadUrl, <pkg>.dedupe): JS-side timing is skewed
             because the second call only runs after the first page turn's work finishes. */
          return orig.apply(this, arguments);
        };
      });
      a.__wcTurnWrapped = true;
      log('page-turn de-dup installed');
    } else if (!a || !a.__wcTurnWrapped) {
      setTimeout(wrapApp, 500);
    }
  })();
  var proxy = {};
  names.forEach(function (m) {
    proxy[m] = function (a, b, c) {
      if (m === 'didMoveToLocation' || m === 'didLoadToLocation') clearBusy();
      /* didTouchStart/EndContentView: in 2.4.0.0 the receiving handlers (EPubViewActivity.
         ePubViewDidTouchStart/EndContentView) are empty, but JsWrapper still posts each one to the
         main thread and builds a ContentEvent, whose constructor creates a brand-new Jackson
         ObjectMapper + KotlinModule and deserializer per call (hegelty measured 330-1000ms each on
         API 19). Two of the three Jackson parses per tap are pure waste -> do not forward them.
         Only didTouchContentView (tap -> handleTouchX/Y) is used. */
      if (m === 'didTouchStartContentView' || m === 'didTouchEndContentView') {
        if (verbose) log('native.' + m + ' dropped');
        return;
      }
      if (verbose && watch.test(m)) log('native.' + m + '(' + String(a).slice(0, 120) + ')');
      var n = arguments.length;
      return n === 0 ? real[m]() : n === 1 ? real[m](a) : n === 2 ? real[m](a, b) : real[m](a, b, c);
    };
  });
  try { window.native = proxy; log('native bridge proxied: ' + (window.native === proxy)); } catch (e) { log('proxy failed ' + e); }
})();

/* ---- searchPage: old -webkit-box never shrinks children, so label(width:100%) + button(45px)
   overflow and the 검색/취소 button is pushed off-screen. Let the label take the remaining width. ---- */
(function () {
  if (!/\/searchPage\//.test(location.href)) return;
  var css = '.search-label{width:auto!important;-webkit-box-flex:1}' +
    '.search-label .search-input{width:auto!important;-webkit-box-flex:1;min-width:0}' +
    '.form-btn{-webkit-box-flex:0}' +
    /* result rows: .book(-webkit-box) = image 94px + .book-data(width:100%) -> long titles overflow */
    '.book-item .book{-webkit-box-flex:1;min-width:0;width:100%}' +
    '.book-item .book .book-image{-webkit-box-flex:0}' +
    '.book-item .book .book-data{width:auto!important;-webkit-box-flex:1;min-width:0}';
  function add() {
    var h = document.head || document.getElementsByTagName('head')[0];
    if (!h) return setTimeout(add, 50);
    var st = document.createElement('style'); st.type = 'text/css'; st.appendChild(document.createTextNode(css)); h.appendChild(st);
  }
  add();
})();
