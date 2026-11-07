---
name: plain-language
description: 'Internal Slack answer companion: explain the reader’s actual concern in clear language while preserving evidence, conditions and uncertainty. Invoked by Dougle bot; not a separate user command.'
user-invocable: false
license: MIT
---

# Dougle Slack integration

Apply this skill when preparing a Dougle Slack reply. Always use respectful Korean 존댓말.
First complete the evidence checks required by dougle-ask. This skill changes presentation, never
research depth, read-only permissions, source verification or the runtime’s configured posting policy.
For bot replies, keep citations and source lists in private structured fields and omit any signature.
Automatic mode does not ask for approval on verified normal answers; explicit review mode does.
Personal-account schedule approval and signature rules do not apply to bot replies.
Infer the audience from the question; default to an adult non-specialist colleague. Do not ask
background questions when the existing context is enough.

Answer the customer's concern first: possibility, impact, timing, location or resolution.
Translate implementation terms into their practical effect. Keep material conditions and the next
action when supported. Do not invent causes, workarounds, estimates or completion times. Brief
does not mean incomplete; omit unrelated internals, not necessary evidence or uncertainty.
Keep provenance in the bot's private sources field; preserve facts in the public answer.
Use i-have-adhd for readable structure and the original plain-language method below for explanation.
Do not invoke this skill, dougle-ask or other companions recursively. Do not mention these internal
skills in the Slack reply. The bot verifies successful current-call Skill results, including resume.

Upstream: [arjunprabhulal/agent-skills](https://github.com/arjunprabhulal/agent-skills/blob/5223b745ca356a04a42250ee316cd8723775f43f/skills/docs/plain-language/SKILL.md),
commit 5223b745ca356a04a42250ee316cd8723775f43f. Invocation metadata and the integration scope above are adapted;
the original procedure below is retained. [MIT license](LICENSE.md).

# Plain language

The goal is not simpler words. It is a reader who ends up genuinely understanding, including
understanding the limits of what they now know.

Two opposite failures. **Untranslated**: jargon left intact, so the reader nods along and takes
away nothing. **Hollowed out**: every precise term replaced with a vague one, leaving prose that
is easy to read and impossible to act on, and worse, a reader confident about something they
have misunderstood.

Simplify the *language and the path*, never the truth.

## 1. Find out what the reader already has

The single biggest determinant of a good explanation is the starting point. An executive, a
junior engineer, and a customer need three different explanations of the same system, and the
difference is not vocabulary — it is which questions they are asking.

Establish:
- **What do they know that is adjacent?** The best explanations build from something they
  already have.
- **What will they do with this?** Deciding, using, approving, or just following along? An
  explanation for a decision must include trade-offs; one for use must include steps.
- **How deep do they need to go?** "Enough to approve the budget" and "enough to debug it" are
  different documents.

**Done when:** you can name the reader's starting knowledge and their purpose.

## 2. Find the load-bearing idea

Most technical explanations fail because they explain everything at equal depth. Find the one
idea that, once understood, makes the rest follow, and spend your budget there.

For a caching layer it is *we keep a copy of the answer so we do not redo the work*. Everything
else — eviction, TTL, invalidation — is a consequence a reader can then absorb quickly.

**Done when:** you can state the core idea in one sentence with no jargon.

## 3. Handle each piece of jargon deliberately

Three options, chosen per term — do not apply one policy to all:

- **Replace** when a plain phrase is genuinely equivalent. "Runs at the same time" for
  "concurrent". No loss, so no reason to keep the term.
- **Teach** when the term is load-bearing and the reader will meet it again. Define it once,
  in context, then use it. Withholding a term the reader needs is a disservice — they cannot
  search for a concept you refused to name.
- **Cut** when it does not serve this reader at all. Most jargon in most drafts is in this
  category, present because the writer knew it rather than because the reader needs it.

Never leave a term undefined on the assumption it is obvious. The curse of knowledge makes
this invisible from the inside — if you learned it more than a year ago, you cannot feel its
difficulty any more.

**Done when:** every remaining technical term is either defined or genuinely common.

## 4. Use analogies, and then close them

A good analogy transfers structure from something familiar. A bad one transfers the wrong
structure and creates a misconception that is harder to remove than the original ignorance.

- Map it to something the reader **actually** knows, not to another technical domain
- Match the **relationships**, not the surface
- **Say where it breaks.** Every analogy fails somewhere, and naming the failure point is what
  makes it safe. "Like a filing cabinet — except many people read and write at once, which is
  where the complications start."

One well-closed analogy beats three loose ones.

**Done when:** each analogy has its limit stated.

## 5. Rebuild the sentences

Dense technical writing has recognisable, fixable habits:

- **Nominalisation:** "perform an evaluation of" → "evaluate". Verbs buried as nouns are the
  single largest source of density.
- **Passive voice hiding the actor:** "the request is validated" → "the gateway validates the
  request". The reader needs to know who does what.
- **Stacked modifiers:** "distributed real-time event stream processing pipeline". Unstack it
  into a sentence.
- **Long chains of clauses:** one idea per sentence. Length is a symptom; the disease is
  multiple ideas competing.
- **Abstraction with no example:** follow every abstract claim with one concrete instance.
  This is the highest-yield fix available.

**Done when:** you can read it aloud without backtracking.

## 6. Keep the precision that matters

Simplifying must not delete:

- **Numbers and conditions:** "faster" is not a simpler version of "about 3x faster on reads"
- **Uncertainty:** "we think" must not become "it is". Overstated confidence is the most
  damaging thing a simplification can introduce
- **Caveats that change decisions:** if a caveat would alter what the reader does, it stays,
  in plain words
- **The distinction between "cannot" and "does not yet"**

When something genuinely cannot be simplified without breaking it, say so: "the short version
is X; the accurate version needs Y, which takes a paragraph." Offering the reader that choice
respects them more than a comfortable falsehood.

## Checking it worked

Read it as the reader. Where would they stop and ask a question? That is where to add a
sentence.

Then check the reverse failure: is there anything here that would let a reader confidently do
the wrong thing? A simplification that reads beautifully and misleads is worse than the dense
original, because the dense one at least signalled that it needed care.

