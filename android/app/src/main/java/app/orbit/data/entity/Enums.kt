package app.orbit.data.entity

enum class ListType { STATIC, SMART }

enum class CallDirection { OUTGOING, INCOMING }

// CALL_LOG — a real call observed in the Android call log (connected, >=1s).
// MANUAL — a connection the user logged by hand (another app, in person).
// ATTEMPT — a reach-out that did NOT connect (outgoing no-answer / voicemail);
//   it advances the rotation a little (flat short cooldown) but is never a
//   connection: it does not set "last contacted" or feed the heat histogram.
enum class CallSource { CALL_LOG, MANUAL, ATTEMPT }

enum class RuleKind { KEEP_IN_TOUCH, LATE_NIGHT, ENERGIZE }
