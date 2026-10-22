// Mini runtime per i mockup .dc.html: sc-for, sc-if e {{espressioni}}
document.addEventListener('DOMContentLoaded', () => {
  const src = document.querySelector('script[data-dc-script]');
  let vals = {};
  if (src) {
    const Component = new Function(`class DCLogic { constructor(){ this.state = {}; } setState(){} }\n${src.textContent}\nreturn Component;`)();
    vals = new Component().renderVals();
  }
  const ev = (expr, scope) => { try { return new Function('s', `with(s){ return (${expr}); }`)(scope); } catch (e) { return ''; } };
  const sub = (text, scope) => text.replace(/{{([^}]*)}}/g, (_, e) => { const v = ev(e, scope); return typeof v === 'function' ? '' : (v ?? ''); });
  function render(node, scope) {
    if (node.nodeType === 3) { if (node.textContent.includes('{{')) node.textContent = sub(node.textContent, scope); return; }
    if (node.nodeType !== 1) return;
    const tag = node.tagName.toLowerCase();
    if (tag === 'sc-for' || tag === 'sc-if') {
      const frag = document.createDocumentFragment();
      if (tag === 'sc-for') {
        const list = ev(node.getAttribute('list').replace(/^{{|}}$/g, ''), scope) || [];
        const as = node.getAttribute('as');
        list.forEach((item, i) => {
          for (const c of node.childNodes) { const k = c.cloneNode(true); frag.appendChild(k); render(k, Object.assign(Object.create(scope), { [as]: item, index: i })); }
        });
      } else if (ev(node.getAttribute('value').replace(/^{{|}}$/g, ''), scope)) {
        for (const c of [...node.childNodes]) { frag.appendChild(c); render(c, scope); }
      }
      node.replaceWith(frag);
      return;
    }
    for (const a of [...node.attributes]) if (a.value.includes('{{')) {
      const only = a.value.match(/^{{([^}]*)}}$/);
      if (only && typeof ev(only[1], scope) === 'function') node.removeAttribute(a.name); else node.setAttribute(a.name, sub(a.value, scope));
    }
    for (const c of [...node.childNodes]) render(c, scope);
  }
  document.querySelectorAll('x-dc').forEach(x => { x.querySelectorAll('helmet').forEach(h => { document.head.append(...h.childNodes); h.remove(); }); render(x, vals); });
});
