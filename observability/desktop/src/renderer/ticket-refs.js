// Ticket references in First mate replies (issue #374): split reply text into
// plain strings and {repo, number} references, in order. A bare #N is a
// frakworks ticket; a markdown link
// [#N](frakworks://ticket/owner/name/N) shows as #N alone (issue #100124); owner/repo#N and a GitHub issue/PR URL name their repo.
// The repo a row belongs to when the row itself does not say (issue #1022,
// multi-repo stage 5).
export const FRAMEWORK_REPO = "codefrak-oss/frakworks";

const REF_RE = /\[#(\d+)\]\(frakworks:\/\/ticket\/([\w.-]+\/[\w.-]+)\/(\d+)\)|https:\/\/github\.com\/([\w.-]+\/[\w.-]+)\/(?:issues|pull)\/(\d+)|([\w.-]+\/[\w.-]+)?#(\d+)\b/g;
// A reference glued to a preceding word or URL character (abc#12,
// page.html#3) is not one.
const GLUED = /[\w.\/:#&?=%-]/;

export const splitTicketRefs = (text) => {
  const out = [];
  let last = 0;
  for (const m of String(text || "").matchAll(REF_RE)) {
    const before = m.index > 0 ? text[m.index - 1] : "";
    if (before && GLUED.test(before)) continue;
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[1]) out.push({ text: `#${m[1]}`, repo: m[2], number: Number(m[3]) });
    else out.push({ text: m[0], repo: m[4] || m[6] || FRAMEWORK_REPO, number: Number(m[5] || m[7]) });
    last = m.index + m[0].length;
  }
  if (last < String(text || "").length) out.push(text.slice(last));
  return out;
};
