package app.orbit.ui.components

/**
 * The monogram an avatar shows when there is no photo: the first letter of up
 * to two words of the name, upper-cased ("Kai Nakamura" reads "KN").
 *
 * One function because one avatar is drawn in three places that cannot share a
 * composable (UX rubric decision 5): the in-app [Avatar], the home-screen
 * widgets (Glance) and a nudge's large icon (a bitmap). Each used to derive its
 * own letters; the widget showed one square initial while the app showed two.
 * The colour comes from the same place for all three as well:
 * [app.orbit.ui.theme.OrbitTones.avatarPalette], keyed on the same name.
 */
fun avatarInitials(name: String): String =
    name.split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
