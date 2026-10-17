// ask-card.jsx — where a ticket's wait is decided, from the engine's hint on
// the comment that announces it (`IntakeQuestions.answerHint`: "HITLP task <id>
// on the engine's HITLP server", or "Decide it on <url>"). The read-only
// decision card that drew it is gone (#100070): a waiting ticket now mounts
// the live task inline (hitlp-task.jsx `TicketTask`), and this file only names
// which task.

const DECIDE_URL = /Decide it on (\S+)/;
const DECIDE_TASK = /HITLP task (\S+) on the engine's HITLP server/;

/**
 * The HITLP task id from the issue's comments (GET /ticket's, oldest first):
 * the newest comment that carries the engine's hint decides, and a hint with
 * a decision URL and no task id (or no hint) gives null, so the caller looks
 * the task up by its ticket in the open-task list instead.
 */
export const hintTaskIdOf = (comments) => {
  const list = Array.isArray(comments) ? comments : [];
  for (let i = list.length - 1; i >= 0; i--) {
    const body = String((list[i] && list[i].body) || "");
    const t = DECIDE_TASK.exec(body);
    if (t) return t[1].replace(/[`.,;:)]+$/, "").replace(/^`/, "");
    if (DECIDE_URL.test(body)) return null;
  }
  return null;
};
