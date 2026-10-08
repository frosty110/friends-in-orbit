# Your week (the Week screen)

**Route:** `week/{listId}`. `listId` is the list whose calls it shows; it always opens on this week
**Group:** Core loop
**Status:** active
**Last reviewed:** 2026-10-07
**Spec:** [home](../home/README.md): HOME-13 (added 2026-10-07), HOME-7, HOME-8, HOME-12; PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: "See your week" on a list card's rhythm strip, the strip's header line
- Home: "See the whole week" at the foot of a strip's day sheet; every day the strip shows is in this week, so it opens there

## What the user sees

- App bar: a back arrow ("Back") and the list's name
- The week's heading between two arrows ("Previous week", "Next week"): "This week" for the seven days ending today, the same seven days as the strip on Home; an earlier week by its dates, "28 Sep to 4 Oct" ("29 Dec 2025 to 4 Jan 2026" when it reaches into another year). Next is off on this week; previous is off on the week that holds the list's earliest call, as far back as Orbit has read the call history
- The key to the outlines, "You" and "Them", the strip's own swatches; and "This week" beside it when an earlier week is showing
- Seven day columns, each headed by its weekday letter and date ("W" over "7"); today in ink and heavier type, the others muted, never in the accent
- A time axis running down the page from midnight at the top to midnight at the bottom: a line every hour, the first below the top edge at 1am, and labels every three hours in the phone's clock style, "3am", "6am", "9am", "12pm", "3pm", "6pm", "9pm" (or "03:00" to "21:00" on a 24-hour phone), sitting on their lines at the start edge. About 40dp an hour; the chart scrolls up and down, and opens a little above the week's earliest call, or at 8am when the week has none. The day heads stay in view while it scrolls
- Each call of three minutes or more (the strip's calls, never others): a block in its day's column at the time it started, as tall as it lasted (a short call still a small block), filled with the person's colour and outlined pink for a call you made, teal for one they made, with the strip's dark ring between. Calls close together sit side by side. A block with room shows the person's first name on a small light chip. A call that ran past midnight stops at the bottom of its day
- The screen spends no accent (rules.md Design 5)
- TalkBack hears each day as one item, its head: "Wednesday 30 September, 2 calls: Alex Kim called you at 1:15pm for 9 min. You called Sarah Chen at 6:40pm for 14 min.", or "Friday 2 October, No calls". The heading is announced when an arrow changes the week. The blocks and the hour labels are not read on their own; the day says every call

## Actions and menus

- The arrows: step one week back or forward. A sideways swipe on the chart does the same: swipe right for the week before, left for the week after. With animations off in Android, the week changes at once
- "This week": back to the seven days ending today
- Tap a block: opens that person's page
- Tap a day with calls, on its head or anywhere in its column but a block ("See this day" to TalkBack): the strip's day sheet for that day, headed "Today", "Yesterday" or "Saturday 3 October", with the summary line ("You called 1 · They called 1") and one row per call that opens the person. Here the sheet has no "See the whole week"
- A quiet day cannot be tapped
- There is no menu

## States

- Loading: quiet chrome, the bar with its back arrow and nothing else, until the calls are read; never a false "No calls this week."
- Ready: as above
- A week with no calls: "No calls this week." over the empty grid, which still shows the days and the hours
- A failed read: "Orbit couldn't load this week" / "Nothing is lost. Try again in a moment." with Try again
- A list that is gone, or a link that names no list: the same message with "Go back" alone
- Privacy curtain: the title reads "List", no block shows a name, and TalkBack hears "You called someone at 6:40pm for 14 min." and "Someone called you at ..." (PRIV-03); the day sheet reads "Someone", as it does on Home
- Left open past midnight: on return, "This week" is the new seven days, matching Home's strip

## Leads to

- Back: Home, where it was opened
- A person's page (a block, or a row of a day's sheet); Back returns here, on the same week

## Tests that pin it

- `WeekLayoutTest` (added 2026-10-07: a 6:40pm call in its column at 6:40pm; a call past midnight on its start day, stopping at the bottom edge; both daylight-saving days; the 3-minute floor; this week is the strip's seven days; how far back the weeks go; a block's floor and 48dp target; side by side when targets would overlap)
- `WeekWordsTest` (added 2026-10-07: a day read to TalkBack, with and without the curtain, and a quiet day; an earlier week's dates; the axis in 12 and 24 hour time)
- `WeekViewModelTest` (added 2026-10-07: Loading, Ready with every week, Go back alone for a malformed id and a missing list, Try again after a failed read, today moving on with a resume)
- `WeekContentTest` (added 2026-10-07: the arrows' names and when they are off; stepping back and "This week"; a day as one node that opens its sheet; a quiet day inert; a three-minute call's 48dp target opens the person; a long block's first name; the curtain; the empty week; Go back)
- `HomeFeedRhythmTest` (added 2026-10-07: this week here is the strip, call for call)
- `HomeContentTest`, `OrbitNavHostTest` and `RoutesTest` (added 2026-10-07: the two ways in from Home, Back, and a person from here)
- Gallery previews: `WeekContentPreview`, `WeekContentLargeTextPreview`, `WeekContent24HourPreview`, `WeekContentEarlierWeekPreview`, `WeekContentEmptyPreview`, `WeekContentCurtainPreview`, `WeekContentLoadingPreview`, `WeekContentErrorPreview`, `WeekContentGoBackPreview`, with the curtain pass
