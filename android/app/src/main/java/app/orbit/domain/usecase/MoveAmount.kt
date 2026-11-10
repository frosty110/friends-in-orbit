package app.orbit.domain.usecase

import java.time.Duration

/**
 * How far Later and Sooner move someone on one list (CARD-02): a day each
 * way on a list of up to [SMALL_LIST_MAX] people, three days on a bigger
 * one. The owner, 2026-10-08: "The sooner/later mechanism needs to be
 * generic to the user but likely 1 day/3 days depending on the size of the
 * list. But we don't surface how much." Built as proposed on 2026-11-10,
 * when the owner said "Finish this up". Nothing on screen says the amount.
 *
 * The one definition [SkipContactUseCase] and [SurfaceSoonerUseCase] both
 * read, so the numbers change here and nowhere else. Until 2026-11-10 each
 * worked out its own amount from the list rule's `skipPenaltyHours`: Later
 * added it (24 hours on a Keep in touch list) and Sooner took off half of
 * it, at least an hour, which the owner found "very short".
 *
 * [memberCount] is the number the Lists row says ("12 people"), read from
 * the same query (`ListRepository.observeMemberCountsByListId`), so the
 * size that decides a move and the size the user reads cannot disagree.
 */
fun moveAmountFor(memberCount: Int): Duration =
    if (memberCount <= SMALL_LIST_MAX) SMALL_LIST_MOVE else LARGE_LIST_MOVE

/** The biggest list, in people, whose people move by [SMALL_LIST_MOVE]. */
private const val SMALL_LIST_MAX = 10

/** A day here is 24 hours (`Duration.ofDays(1)`), not a calendar day. */
private val SMALL_LIST_MOVE: Duration = Duration.ofDays(1)

private val LARGE_LIST_MOVE: Duration = Duration.ofDays(3)
