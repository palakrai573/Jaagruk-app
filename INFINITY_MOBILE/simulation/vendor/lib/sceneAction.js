/*
 * What the 3D scene should DO when a worker answers.
 *
 * THE PROBLEM THIS SOLVES
 *
 * The briefing scene showed a fire, the worker chose "use the CO2 extinguisher",
 * and the scene showed a fire. Nothing happened. The 3D view was a static diagram
 * that happened to rotate, which is worse than no 3D at all: it looks like a
 * simulation, so a trainee reasonably expects it to respond, and its not responding
 * reads as the app being broken rather than as the app being a slideshow.
 *
 * WHY THIS MAPS FROM THE PICTOGRAM RATHER THAN FROM THE STEP
 *
 * There are 54 decisions with 2 or 3 choices each, so hand-authoring a visual per
 * choice would be roughly 130 entries to write and keep in step with the content.
 * Every choice already carries a `pictogram` — the icon drawn on its own button —
 * and that icon already names the action: `extinguisher`, `exit`, `lockout`,
 * `gas_mask`. So the visual follows the icon the worker just pressed, which means
 * it cannot drift out of agreement with what they were shown, and new content gets
 * a sensible animation for free.
 *
 * The cost of that choice, stated: the mapping is coarse. Two different correct
 * answers that share an icon get the same animation. That is a fair trade against
 * 130 hand-written entries that would rot.
 *
 * SAFE VERSUS UNSAFE
 *
 * A wrong answer does not play its own action — it escalates the hazard. Showing
 * the fire spread when a worker chooses to open the door on a hot fire is the whole
 * pedagogical point, and showing the same tidy animation for right and wrong
 * answers would teach that the choice did not matter.
 */

/** What the scene animates. Deliberately a small vocabulary. */
export const SCENE_ACTION = {
  /** Agent discharged, fire dies. */
  EXTINGUISH: 'EXTINGUISH',
  /** Worker leaves toward the exit. */
  EVACUATE: 'EVACUATE',
  /** Machine stops and is locked off. */
  ISOLATE: 'ISOLATE',
  /** Beacon and siren: raise the alarm, tell someone. */
  ALERT: 'ALERT',
  /** Put on the right respirator or PPE. */
  PROTECT: 'PROTECT',
  /** Back away and hold the line. */
  RETREAT: 'RETREAT',
  /** Water mist onto a dust source. */
  SUPPRESS: 'SUPPRESS',
  /** Stop and check — test, measure, inspect, listen. */
  ASSESS: 'ASSESS',
  /** Go to a casualty or call a buddy. */
  ASSIST: 'ASSIST',
  /** Approach or operate the hazard. Almost always the wrong answer. */
  ENGAGE: 'ENGAGE',
  /** Nothing recognised — the scene holds still rather than inventing a motion. */
  NONE: 'NONE',
}

/*
 * Every choice pictogram in the content maps to exactly one action. Kept explicit
 * rather than pattern-matched on substrings, because `do_not_operate` and
 * `do_not_enter` differ by one word and mean different things on screen: one stops
 * a machine, the other stops a person.
 *
 * tests/sceneAction.test.mjs fails if the content ever introduces a pictogram that
 * is not listed here, so a new module cannot silently fall back to a still scene.
 */
export const ACTION_FOR_PICTOGRAM = Object.freeze({
  extinguisher: SCENE_ACTION.EXTINGUISH,

  exit: SCENE_ACTION.EVACUATE,
  exit_arrow: SCENE_ACTION.EVACUATE,
  assembly_point: SCENE_ACTION.EVACUATE,

  lockout: SCENE_ACTION.ISOLATE,
  do_not_operate: SCENE_ACTION.ISOLATE,

  alarm: SCENE_ACTION.ALERT,
  report_it: SCENE_ACTION.ALERT,

  gas_mask: SCENE_ACTION.PROTECT,
  dust_mask: SCENE_ACTION.PROTECT,
  ppe: SCENE_ACTION.PROTECT,

  do_not_enter: SCENE_ACTION.RETREAT,
  cross: SCENE_ACTION.RETREAT,

  wet_suppression: SCENE_ACTION.SUPPRESS,

  check: SCENE_ACTION.ASSESS,
  detector: SCENE_ACTION.ASSESS,
  gas_detector: SCENE_ACTION.ASSESS,
  medical_check: SCENE_ACTION.ASSESS,
  listen: SCENE_ACTION.ASSESS,
  clock: SCENE_ACTION.ASSESS,
  proper_lift: SCENE_ACTION.ASSESS,

  buddy: SCENE_ACTION.ASSIST,
  first_aid: SCENE_ACTION.ASSIST,
  worker: SCENE_ACTION.ASSIST,

  /*
   * These are hazard icons. They appear on a choice when the choice is to go and
   * interact with the hazard — pick up the damaged ladder, operate the machine,
   * walk into the confined space. Occasionally such a choice is the correct one, so
   * ENGAGE is not synonymous with "wrong"; correctness comes from the points, not
   * from the icon.
   */
  fire: SCENE_ACTION.ENGAGE,
  machinery: SCENE_ACTION.ENGAGE,
  electric: SCENE_ACTION.ENGAGE,
  dust: SCENE_ACTION.ENGAGE,
  forklift: SCENE_ACTION.ENGAGE,
  spark: SCENE_ACTION.ENGAGE,
  unstable_load: SCENE_ACTION.ENGAGE,
  damaged_ladder: SCENE_ACTION.ENGAGE,
  ladder: SCENE_ACTION.ENGAGE,
  confined_space: SCENE_ACTION.ENGAGE,
  warning: SCENE_ACTION.ENGAGE,
})

/**
 * Resolve a chosen answer into a scene action.
 *
 * @param choice  the choice object the worker pressed (needs `pictogram`, `points`)
 * @param step    the step it belonged to (needs `maxPoints`)
 * @returns { kind, safe } — or null when there is nothing to animate
 */
export function actionForChoice(choice, step) {
  if (!choice) return null

  const pictogram = typeof choice.pictogram === 'string' ? choice.pictogram : ''
  const kind = ACTION_FOR_PICTOGRAM[pictogram] || SCENE_ACTION.NONE

  /*
   * "Safe" means full marks, matching how the drill itself decides. A partially
   * correct answer counts as unsafe here on purpose: the scene should not reward a
   * half-right decision with the tidy animation, because in the field a half-right
   * decision is how people are hurt.
   */
  const maxPoints = Number(step?.maxPoints) || 0
  const points = Number(choice.points) || 0
  const safe = maxPoints > 0 && points >= maxPoints

  return { kind, safe }
}

/**
 * Does this action visibly resolve the hazard?
 *
 * Used by the scene to decide whether the hazard should die down or escalate. An
 * unsafe answer never resolves anything, whatever its icon suggested.
 */
export function resolvesHazard(action) {
  if (!action || !action.safe) return false
  return (
    action.kind === SCENE_ACTION.EXTINGUISH ||
    action.kind === SCENE_ACTION.ISOLATE ||
    action.kind === SCENE_ACTION.SUPPRESS
  )
}

/** Should the hazard get worse? Any unsafe answer, and engaging a hazard at all. */
export function escalatesHazard(action) {
  if (!action) return false
  return !action.safe
}
