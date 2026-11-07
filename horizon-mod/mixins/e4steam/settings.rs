// HEAD of `native_settings`: the page's buttons, asked again when it opens and after every press, so
// they follow the session instead of the last time someone looked.
fn steam_settings(
    game: &mut crate::game::Game<'_>,
    mod_id: &str,
    buttons: &mut Vec<String>,
) {
    crate::game::e4steam::on_settings(game, mod_id, buttons);
}
