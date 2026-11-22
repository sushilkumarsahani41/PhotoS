'use strict';
// Understands dates typed into search (day first): "2023", "may", "may 2023", "05/2023", "2023-05", "12/05/2023",
// "12-05-23", "12 may", "12 may 2023", "2023-05-12", "today", "yesterday", "this month", "last month", "this year",
// "last year". A missing part matches any value. Same rules as the Android app (DateQuery.kt).
(function (root) {
  const MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];
  const month = (w) => { w = w.replace(/\.$/, ''); if (w.length < 3) return null; const i = MONTHS.findIndex((m) => m.startsWith(w)); return i >= 0 ? i + 1 : null; };
  const year = (t) => (/^\d{4}$/.test(t) ? (+t >= 1900 && +t <= 2100 ? +t : null) : /^\d{2}$/.test(t) ? 2000 + +t : null);
  function valid(y, m, d) {
    if (m != null && (m < 1 || m > 12)) return null;
    if (d != null && (d < 1 || d > 31)) return null;
    if (y != null && m != null && d != null) { const t = new Date(y, m - 1, d); if (t.getMonth() !== m - 1) return null; }
    return { year: y ?? null, month: m ?? null, day: d ?? null };
  }
  function parse(input, today = new Date()) {
    const q = String(input ?? '').trim().toLowerCase().replace(/\s+/g, ' ');
    if (!q) return null;
    const shift = (days) => { const t = new Date(today.getFullYear(), today.getMonth(), today.getDate() + days); return { year: t.getFullYear(), month: t.getMonth() + 1, day: t.getDate() }; };
    if (q === 'today') return shift(0);
    if (q === 'yesterday') return shift(-1);
    if (q === 'this month') return { year: today.getFullYear(), month: today.getMonth() + 1, day: null };
    if (q === 'last month') { const t = new Date(today.getFullYear(), today.getMonth() - 1, 1); return { year: t.getFullYear(), month: t.getMonth() + 1, day: null }; }
    if (q === 'this year') return { year: today.getFullYear(), month: null, day: null };
    if (q === 'last year') return { year: today.getFullYear() - 1, month: null, day: null };
    let m;
    if ((m = /^(\d{4})$/.exec(q))) { const y = year(m[1]); return y ? { year: y, month: null, day: null } : null; }
    if ((m = /^(\d{4})[-/.](\d{1,2})(?:[-/.](\d{1,2}))?$/.exec(q))) return valid(+m[1], +m[2], m[3] ? +m[3] : null);
    if ((m = /^(\d{1,2})[-/.](\d{4})$/.exec(q))) return valid(+m[2], +m[1], null);
    if ((m = /^(\d{1,2})[-/.](\d{1,2})[-/.](\d{2}|\d{4})$/.exec(q))) { const y = year(m[3]); return y ? valid(y, +m[2], +m[1]) : null; }
    const words = q.replace(/,/g, ' ').split(' ').filter(Boolean);
    if (words.length > 3) return null;
    let y = null, mo = null, d = null;
    for (const w of words) {
      const n = w.replace(/(st|nd|rd|th)$/, '');
      if (mo == null && month(w)) mo = month(w);
      else if (y == null && /^\d{4}$/.test(n) && year(n)) y = year(n);
      else if (d == null && /^\d{1,2}$/.test(n)) d = +n;
      else return null;
    }
    return mo ? valid(y, mo, d) : null;
  }
  const matches = (q, date) => { const t = new Date(date); return (q.year == null || t.getFullYear() === q.year) && (q.month == null || t.getMonth() + 1 === q.month) && (q.day == null || t.getDate() === q.day); };
  function label(q) {
    const m = q.month ? MONTHS[q.month - 1][0].toUpperCase() + MONTHS[q.month - 1].slice(1) : null;
    if (q.year && m && q.day) return `${q.day} ${m} ${q.year}`;
    if (q.year && m) return `${m} ${q.year}`;
    if (m && q.day) return `${q.day} ${m}, any year`;
    if (m) return `${m}, any year`;
    return String(q.year ?? '');
  }
  const api = { parse, matches, label };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.DateQuery = api;
})(this);
