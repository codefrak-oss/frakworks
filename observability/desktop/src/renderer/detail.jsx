// detail.jsx — the ticket detail: the one view the middle pane draws for a
// ticket `{ repo, number }`, whichever list opened it (Tickets > Tickets,
// Tickets > Needs you, a Tickets tree row, a Jev or Doctor link, a saved
// chat, the First mate). It was two views until #802: the Issues board's
// issue detail (flow.jsx) and the tree's ticket detail (ticket.jsx), and only
// the first drew the ask card and Cancel, so what a ticket let you do hung on
// the list it came from. Now the actions depend on the ticket alone.
//
// What it draws, each part from its own read (all props; the screen in
// index.jsx owns the reads):
// - the board's row (`issue`, GET /issues): progress, Parts, and for a
//   proposal with a PR the Pitch view with its decision link;
// - the issue's `/needs` rows (`needs`):
//   for a question, acceptance or decision, the live HITLP task it waits on,
//   answered in place (hitlp-task.jsx TicketTask), and a
//   "waiting to be authorized" notice for an authorize / reauthorize row;
// - the engine's ticket read (`doc`, GET /ticket via ticket:fetch):
//   description, linked PRs, comments, and the off-board facts;
// - `offBoard` (flow.jsx readOffBoard), for an issue the board has no row for;
// - the flow (`flowResult`): its steps, questions, variables;
// - and the parts that read for themselves: Cancel, Process, Timeline,
//   Doctor, the pitch, incident actions, plus the live transcript.
//
// The live transcript has two places (#867). While the Timeline's running
// step is any Smith run (whatever its role), it sits directly under that step's card, inside the
// Timeline, and the card carries its two controls: the ▾/▸ Transcript fold and Summarize
// (#881). The fold drives index.jsx's transcript loop (#883): expanding the pane
// (and the summary panel) turns scraping on, collapsing it turns it off, so
// the pane is never open and paused nor collapsed and tailing. It starts
// collapsed on every ticket, since scraping starts off (#878). With no step
// running but cards drawn (between two runs, or after the last), it stays in
// the Timeline under the most recent card, behind its own fold. Only when the
// Timeline draws no cards is it "Live transcript" after the flow body.
import { useEffect, useState } from "react";
import { FRAMEWORK_REPO } from "./ticket-refs.js";
import { childrenSummary, ProgressBar } from "./issues.jsx";
import { ProcessSection } from "./process.jsx";
import { Timeline, BlockedBanner, runningSmith, transcriptAnchor, stepKey } from "./timeline.jsx";
import { hintTaskIdOf } from "./ask-card.jsx";
import { TicketTask } from "./hitlp-task.jsx";
import { CancelFlow } from "./cancel-flow.jsx";
import { MainBuildStrip } from "./main-build.jsx";
import { TicketWorkItem } from "./work-items.jsx";
import { PitchView, TicketPitch, isProposal, isResearch } from "./pitch.jsx";
import { DesignDeck } from "./design-deck.jsx";
import { ProposalFacts, ProposalLink } from "./proposals.jsx";
import { KIND_TITLES } from "./needs-you.jsx";
import { FlowBody, DoctorSection, pendingNeed, needQuestion, pendingKind } from "./flow.jsx";
import { Description, LinkedPrs, CommentsSection, TranscriptPane, SummaryPanel, summarizeOn, hasTranscript,
  historyRepoOf, readMode, TICK_MS } from "./ticket.jsx";

// The needs kinds the app has no action for here: an intake's authorization is a
// trusted login's start of its ask item (POST /work-items/{id}/start, or First
// mate's approve; engine/docs/intake.md), not an action of this view.
// A ticket's story points, from the doc's issue or the engine's ticket read; null when none.
const pointsOf = (t) => (t && Number.isInteger(t.points) ? t.points : null);

export const AUTHORIZE_KINDS = ["authorize", "reauthorize"];

/** The issue's authorize / reauthorize `/needs` row, or null. */
export const authorizeNeed = (rows) => (rows || []).find((r) => r && AUTHORIZE_KINDS.includes(r.kind)) || null;

// The notice for an authorize row, in the ask card's place: what is waited
// on, in Needs you's words, and where to do it.
export const AuthorizeNotice = ({ row, url }) => (row ? (
  <div className="askCard authorizeCard" role="status">
    <div className="askHead">{KIND_TITLES[row.kind] || row.kind}</div>
    {row.ask && <div className="askSummary">{row.ask}</div>}
    <div className="muted">The app has no authorize action: add the <code>authorized</code> label to
      the issue <a href={url} title="Open on GitHub">on GitHub</a> to let the engine go on.</div>
  </div>
) : null);

// The running card's two controls for the transcript under it: the
// fold, which is scraping on/off as well (#883), and Summarize.
// `summarizing` is a Summarize request in flight.
export const TranscriptControls = ({ open, onOpen, summarizing = false, onSummarize }) => (
  <>
    <button type="button" className="tlCtl tlCtlFold" aria-expanded={open}
            title={open ? "Hide the live transcript" : "Show the live transcript"}
            onClick={() => onOpen && onOpen(!open)}>{open ? "▾" : "▸"} Transcript</button>
    <button type="button" className="tlCtl tlCtlSummarize" disabled={summarizing}
            title="Ask the engine for a summary of the session and what the Smith is doing now"
            onClick={() => onSummarize && onSummarize()}>{summarizing ? "Summarizing…" : "Summarize"}</button>
  </>
);

const labelName = (l) => (l && typeof l === "object" ? l.name : l);
const loginOf = (a) => (a && typeof a === "object" ? a.login : a);

/**
 * The view, without state of its own (TicketDetail below holds it), so a test
 * can call it and walk the tree. `now` is ms. `mode` is the description and
 * comments' rendered/raw; `summarized` the summary panel's state (the running card's Summarize); `blocked` the engine's
 * `ticket.blocked` from the Timeline's last read; `doctor` a GET /doctor/history
 * read for the Doctor section to start from (a test's; it reads its own).
 * `timeline` is the Timeline's last read (or a test's, which the Timeline
 * starts from): its running step (any role) decides where the live transcript
 * goes. `transcriptOpen` / `onTranscriptOpen` fold the pane under the running
 * card, and are index.jsx's transcript loop on or off (TicketDetail wires them).
 * `liveOpen` / `onLiveOpen` fold the "Live transcript" section drawn when no
 * step runs (#1700): view only, collapsed by default, scraping is not touched.
 */
export const TicketDetailView = ({
  repo: repoProp, number, issue = null, doc = null, error = null, offBoard = null, flowResult = null, needs = [],
  transcript = null, intake = null, proposal = null, onSelect, onBack, onRefresh, refreshing, now = Date.now(),
  onChatAbout, mode = "rendered", onMode, summarized = null, onSummarize, blocked = null, onTimeline, doctor = null,
  timeline = null, transcriptOpen = false, onTranscriptOpen, liveOpen = false, onLiveOpen,
}) => {
  const repo = repoProp || (issue && issue.repo) || (doc && doc.repo) || FRAMEWORK_REPO;
  const di = doc && doc.issue;
  const ob = offBoard && offBoard.ticket;
  const url = (issue && issue.url) || (di && di.url) || (ob && ob.url) || `https://github.com/${repo}/issues/${number}`;
  const title = (issue && issue.title) || (di && di.title) || (ob && ob.title) || "";
  const historyRepo = doc ? historyRepoOf(doc) : repo;
  const [hOwner, hName] = historyRepo.split("/");
  const f = issue && issue.flow;
  // A research ticket on the board (#1262) draws the Pitch view too: its page under "Answer", with the ack controls.
  const research = isResearch(issue);
  const pitch = isProposal(issue) || research;
  // Chat about this (#699): a ticket-scoped First mate conversation; a
  // proposal keeps its Pitch view's button instead, so one is drawn either way.
  const chat = onChatAbout && repo && number && !pitch
    ? <button type="button" className="ticketChat" title="Start a new First Mate conversation about this ticket"
              onClick={() => onChatAbout({ kind: "ticket", repo, number, title })}>Chat about this</button>
    : null;
  const head = (
    <div className="head">
      <h1>
        <button className="backBtn" title="Back" onClick={() => onBack && onBack()}>‹</button>
        #{number}
        <a className="ghLink" href={url} title="Open on GitHub">GitHub ↗</a>
      </h1>
      <span className="meta">
        {chat}
        <CancelFlow issueUrl={url} onDone={onRefresh} />
        <button className={"refreshBtn" + (refreshing ? " spinning" : "")} title="Refresh now"
                disabled={refreshing} onClick={() => onRefresh && onRefresh()}>⟳</button>
      </span>
    </div>
  );

  // The board's question, else the issue's answerable /needs row (a flow
  // waiting with no pendingQuestion on the board row, or an issue off the
  // board): the same card either way.
  const pendingQ = issue && issue.pendingQuestion;
  const need = pendingQ ? null : pendingNeed(needs);
  const pending = pendingQ || needQuestion(need);
  const kind = pendingQ ? pendingKind(pendingQ, needs) : need ? need.kind : null;
  const decision = pitch && kind === "decision";
  const authorize = authorizeNeed(needs);
  // Which task: the engine's hint on the issue's newest announcing comment, else
  // TicketTask finds it in the open-task list by the ticket.
  const taskId = pending ? hintTaskIdOf(di && di.comments) : null;

  const labels = issue ? issue.labels : di ? di.labels : ob ? (ob.labels || []).map(labelName).filter((l) => typeof l === "string") : [];
  const state = di ? di.state : ob ? ob.state : "";
  // The story points the refiner estimated (GET /ticket's `points`, bots/story-points.md), shown beside the kind and tier labels.
  const points = pointsOf(di) ?? pointsOf(ob);
  const author = issue ? issue.author : ob ? loginOf(ob.author) : null;
  // Who asked for it: the board row's `askedBy`, else GET /ticket's; nothing when neither says.
  const askedBy = (issue && issue.askedBy) || (di && di.askedBy) || (ob && ob.askedBy) || null;

  // The live transcript: under the running step's card, else under the most
  // recent card, else after the flow body.
  const running = runningSmith(timeline);
  const anchored = !!transcriptAnchor(timeline);
  const transcriptBody = <TranscriptPane transcript={transcript} paused={!transcriptOpen} />;
  const runningControls = running
    ? <TranscriptControls open={transcriptOpen} onOpen={onTranscriptOpen}
                          summarizing={!!(summarized && summarized.loading)} onSummarize={onSummarize} />
    : null;
  const runningSlot = running && transcriptOpen
    ? <div className="tlTranscript"><SummaryPanel state={summarized} />{transcriptBody}</div> : null;
  const liveFold = (
    <button type="button" className="tlCtl tlCtlFold" aria-expanded={liveOpen}
            title={liveOpen ? "Hide the live transcript" : "Show the live transcript"}
            onClick={() => onLiveOpen && onLiveOpen(!liveOpen)}>{liveOpen ? "▾" : "▸"}</button>
  );
  const idleSlot = !running && anchored && hasTranscript(transcript)
    ? <div className="tlTranscript">{liveFold} Transcript{liveOpen && transcriptBody}</div> : null;

  return (
    <div className="fleet-root">
      {/* Pinned while the rest of the detail scrolls under it (#342). */}
      <div className="tdPinned">
        {head}
        <div className="tdTitle">{title || <span className="muted">title unavailable</span>}</div>
        {intake && intake.number != null && (
          <div className="tdIntake">Intake {intake.url
            ? <a href={intake.url} title="The intake this ticket was filed from">#{intake.number}</a> : `#${intake.number}`}</div>
        )}
      </div>
      <div className="chips">
        <span className="chip">{repo}</span>
        {state && <span className={"chip tdState " + (String(state).toUpperCase() === "OPEN" ? "open" : "closed")}>{String(state).toLowerCase()}</span>}
        {labels.map((l) => <span key={l} className="chip">{l}</span>)}
        {points != null && <span className="chip tdPoints" title="Story points (bots/story-points.md)">{points} pts</span>}
        {author && <span className="chip">by {author}</span>}
        {askedBy && <span className="chip tdAskedBy">Asked by {askedBy}</span>}
      </div>
      {/* The board lists open issues only (#548): a closed ticket Needs you
          still lists is read from GET /ticket instead. */}
      {!issue && offBoard && (offBoard.loading
        ? <div className="muted">not on the engine's board; asking the engine for the ticket…</div>
        : ob ? null
        : <div className="err">{repo}#{number} is not on the engine's board now (closed, or its repo could not be listed)
            {offBoard.error ? `: ${offBoard.error}` : ""}</div>)}
      {issue && <ProgressBar issue={issue} now={now} className="isProgressLg" />}
      {doc && doc.prs && <MainBuildStrip repo={repo} prs={doc.prs} now={now} />}

      {pending && !decision && <TicketTask repo={repo} number={number} taskId={taskId} />}
      <AuthorizeNotice row={authorize} url={url} />
      {!issue && needs.length > 0 && <>
        <h2>Waiting on you</h2>
        {needs.map((r) => <div key={r.key || `${r.kind}${r.actionId}`} className="muted">{r.kind}: {r.ask}</div>)}
      </>}

      {/* Why an intake stopped, near the top: its Timeline reads it from the engine. */}
      <BlockedBanner blocked={blocked} />

      {/* A proposal the Proposals branch lists (#660): the way to its view and
          its facts. Then the pitch: a proposal on the board draws the Pitch
          view with its decision link; any other ticket its pitch page,
          framed in its dark scheme, or nothing when it has none (#579). */}
      {proposal && <ProposalLink row={proposal} onSelect={onSelect} chip={false} />}
      {proposal && <ProposalFacts row={proposal} onSelect={onSelect} />}
      {pitch
        ? <PitchView repo={issue.repo} number={issue.number} title={issue.title} prUrl={f.prUrl} research={research}
                     decision={decision} taskId={taskId} onChatAbout={onChatAbout} />
        : <TicketPitch repo={historyRepo} issue={number} />}
      {!pitch && <DesignDeck repo={historyRepo} issue={number} />}

      {doc && doc.issue_error && <div className="err">{doc.issue_error}</div>}
      {!doc && (error ? <div className="err">{String(error)}</div> : <div className="muted">asking the engine for the ticket…</div>)}
      {di && <>
        <h2>Description</h2>
        <Description body={di.body} html={doc.body_html} mode={mode} onMode={onMode || (() => {})} />
      </>}
      {/* What the merged work changed (#100141): the engine serves it only for a
          ticket closed with a merged PR, so an open one, or one done before it, draws nothing. */}
      {di && di.state === "CLOSED" && di.implementationSummary && <>
        <h2>Implementation summary</h2>
        <p className="tdImplSummary">{di.implementationSummary}</p>
      </>}
      {doc && ((doc.prs && doc.prs.length) || doc.prs_error) && <>
        <h2>Pull requests</h2>
        <LinkedPrs prs={doc.prs || []} error={doc.prs_error} />
      </>}

      {issue && <>
        {issue.parent && <div className="muted">part of <a href={issue.parent.url}>#{issue.parent.number}</a></div>}
        {issue.children && issue.children.length > 0 && <>
          <h2>Parts</h2>
          <div className="muted">{childrenSummary(issue)}</div>
          <table className="flChildren"><tbody>
            {issue.children.map((c) => (
              <tr key={c.index} className={"is-" + c.state}>
                <td className="num">{c.index + 1}</td>
                <td>{c.number != null ? <a href={c.url}>#{c.number}</a> : ""}</td>
                <td>{c.title}</td>
                <td>{c.kind}</td>
                <td>{c.state}{c.prNumber != null ? ` (PR #${c.prNumber})` : ""}</td>
              </tr>
            ))}
          </tbody></table>
        </>}
      </>}

      {/* The doctor's runs on this ticket, each with its outcome and its
          transcript (flow.jsx DoctorSection, #705), next to the incident
          that may have started one; it reads again on each flow poll and Refresh.
          Fix and Propose a fix (src/renderer/incident-actions.jsx) render inside
          it, between the Doctor heading and the runs, so they fold with the
          section. */}
      <DoctorSection issueUrl={url} poll={flowResult || offBoard || doc} initial={doctor} />

      {/* The engine's process instances for this issue, with the open one's
          diagram (src/renderer/process.jsx). */}
      <ProcessSection issueUrl={url} now={now} />

      {/* One card per Smith run (any role), one line per wait
          (src/renderer/timeline.jsx): the engine's own history of this ticket. */}
      <Timeline owner={hOwner} repo={hName} ticket={number} now={now} onTimeline={onTimeline}
                flow={flowResult && flowResult.flow ? flowResult.flow : null}
                initial={timeline} runningControls={runningControls} runningSlot={runningSlot} idleSlot={idleSlot} />

      {flowResult && flowResult.error && <div className="err">{flowResult.error}</div>}
      {flowResult && flowResult.loading && <div className="muted">asking the engine for the flow…</div>}
      {flowResult && flowResult.flow && <FlowBody flow={flowResult.flow} now={now} />}

      {!anchored && hasTranscript(transcript) && (
        <>
          <h2>Live transcript{" "}{liveFold}</h2>
          {liveOpen && transcriptBody}
        </>
      )}

      {doc && <CommentsSection comments={(di && di.comments) || []} error={doc.issue_error}
                               html={doc.comment_html || {}} mode={mode} />}

      {/* Its work item's conversation and the reply box (#100029: once Engine > Work items'). */}
      {number != null && <TicketWorkItem repo={repo} number={number} />}
    </div>
  );
};

/**
 * The ticket detail. `now` (ms) is for tests; without it the view ticks once
 * a second, for the Timeline's and the progress bar's live counters.
 */
/**
 * The summary panel's state while `timeline`'s running step is the step it
 * was asked for (its `step`, a stepKey), else null (#945): a summary belongs
 * to its run, not to whatever card runs next.
 */
export const summaryFor = (summarized, timeline) =>
  summarized && summarized.step === stepKey(runningSmith(timeline)) && summarized.step != null ? summarized : null;

export const TicketDetail = (props) => {
  const { repo, number, now } = props;
  const [tick, setTick] = useState(() => Date.now());
  useEffect(() => {
    if (now != null) return undefined;             // tests drive `now` themselves
    const t = setInterval(() => setTick(Date.now()), TICK_MS);
    return () => clearInterval(t);
  }, [now]);
  // rendered/raw, shared by the description and the comments
  const [mode, setMode] = useState(readMode);
  // The summary panel's state (SummaryPanel) with the step it was asked for
  // (`step`, a stepKey), cleared when the view moves to another ticket or that
  // step is no longer the running one (#945; summaryFor). A test may start from `props.summarized`.
  const [summarized, setSummarized] = useState(() => props.summarized || null);
  useEffect(() => setSummarized(null), [repo, number]);
  // The engine's `ticket.blocked` from the last timeline Timeline loaded
  // (an intake the refiner said `insufficient-permissions` on, #443), cleared on a new ticket.
  const [blocked, setBlocked] = useState(null);
  useEffect(() => setBlocked(null), [repo, number]);
  // The last timeline Timeline loaded (a test's to start from), for where
  // the live transcript goes; cleared on a new ticket.
  const [timeline, setTimeline] = useState(() => props.timeline || null);
  useEffect(() => setTimeline(null), [repo, number]);
  // The non-running "Live transcript" section's fold (#1700): collapsed on each ticket.
  const [liveOpen, setLiveOpen] = useState(() => props.liveOpen || false);
  useEffect(() => setLiveOpen(false), [repo, number]);
  // The running card's fold is scraping on/off (#883): one state, index.jsx's.
  // Scraping off is a control on the running card only: once no step
  // runs, the card and its control are gone, so the loop is turned back on
  // rather than left paused with no way to resume it. Not before the
  // timeline has loaded: scraping starts off (#878), and a ticket whose
  // running card is still on its way must not be turned on meanwhile.
  const runningKey = runningSmith(timeline) ? `${repo}#${number}` : null;
  const { scraping = false, onScraping, ...viewProps } = props;
  useEffect(() => {
    if (timeline && !runningKey && !scraping && onScraping) onScraping(true);
  }, [timeline, runningKey, scraping, onScraping]);
  const historyRepo = props.doc ? historyRepoOf(props.doc) : repo;
  return (
    <TicketDetailView {...viewProps} now={now != null ? now : tick} mode={mode} onMode={setMode}
      summarized={summaryFor(summarized, timeline)} blocked={blocked} timeline={timeline}
      transcriptOpen={scraping} liveOpen={liveOpen} onLiveOpen={setLiveOpen}
      onTranscriptOpen={(open) => onScraping && onScraping(open)}
      onTimeline={(t) => {
        // A load that drew no timeline keeps the last banner but drops the
        // timeline, so the transcript falls back to its place after Jev.
        if (t) setBlocked((t.ticket && t.ticket.blocked) || null);
        setTimeline(t);
        setSummarized((s) => summaryFor(s, t));
      }}
      onSummarize={async () => {
        // Summarize opens the collapsed pane (#940) through the same callback
        // the fold uses, so scraping goes on and the answer lands where the
        // reader can see it; already open, the fold's state is left alone.
        if (!scraping && onScraping) onScraping(true);
        const step = stepKey(runningSmith(timeline));
        setSummarized({ loading: true, step });
        const answer = await summarizeOn(typeof window !== "undefined" ? window.fleet : null, historyRepo, number);
        // An answer for a step that has since ended is dropped by summaryFor.
        setSummarized((s) => (s && s.step === step ? { ...answer, step } : s));
      }} />
  );
};
