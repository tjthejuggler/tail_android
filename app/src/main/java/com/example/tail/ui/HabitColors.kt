package com.example.tail.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.example.tail.R

// 7 color tiers — progressively brighter/more saturated as count increases
val ColorRed    = Color(0xFF3D1515)   // muted dark red                        — count 0
val ColorOrange = Color(0xFF7A3800)   // distinctly orange, medium-dark        — count 1
val ColorGreen  = Color(0xFF1A4020)   // medium-muted green                    — count 2
val ColorBlue   = Color(0xFF102255)   // medium-muted blue                     — count 3
val ColorPink   = Color(0xFF901060)   // semi-bright neon-ish magenta-pink     — count 4
val ColorYellow = Color(0xFFB8B000)   // bright neon-ish yellow                — count 5
val ColorGlass  = Color(0xFFD0D0E0)   // bright near-white with faint blue     — count 6+

// Brighter border variants — vivid enough to pop against the Glass/white background
val BorderRed    = Color(0xFFCC3333)  // vivid red
val BorderOrange = Color(0xFFE07020)  // vivid orange
val BorderGreen  = Color(0xFF33AA55)  // vivid green
val BorderBlue   = Color(0xFF3366DD)  // vivid blue
val BorderPink   = Color(0xFFDD44AA)  // vivid magenta-pink
val BorderYellow = Color(0xFFDDCC00)  // vivid yellow
val BorderGlass  = Color(0xFFD0D0E0)  // same as Glass — final tier

/** Background + optional color-tier border(s) for a habit button.
 *
 *  Phase 1 (count 0–5):  solid [background], no border.
 *  Phase 2 (count 6):    Glass [background], no border.
 *  Phase 3 (count 7–12): Glass [background] + single [borderColor].
 *  Phase 4 (count 13+):  Glass [background] + double border
 *                        ([outerBorderColor] | thin black | [innerBorderColor]).
 */
data class HabitStyle(
    val background: Color,
    val borderColor: Color? = null,         // single border (Phase 3)
    val outerBorderColor: Color? = null,    // double-border outer ring (Phase 4)
    val innerBorderColor: Color? = null     // double-border inner ring (Phase 4)
)

// 6 vivid border colours used for both the single-border and double-border cycles.
// (BorderGlass is intentionally excluded — an invisible white border is pointless.)
private val vividBorderColors = listOf(
    BorderRed, BorderOrange, BorderGreen, BorderBlue, BorderPink, BorderYellow
)

/**
 * The app's colour progression (Red → Orange → Green → Blue → Pink → Yellow →
 * Glass), exposed for screen-level theming: each habits screen (grid page)
 * is tinted with the next step, and the schedule view's screen-coded chips
 * use the vivid variants of the same order.
 */
val ScreenProgressionColors = listOf(
    ColorRed, ColorOrange, ColorGreen, ColorBlue, ColorPink, ColorYellow, ColorGlass
)

/** Vivid (readable-on-dark) variants of [ScreenProgressionColors], same order. */
val ScreenAccentColors = listOf(
    BorderRed, BorderOrange, BorderGreen, BorderBlue, BorderPink, BorderYellow, BorderGlass
)

/** The progression colour for a habits screen index (cycles past the end). */
fun screenProgressionColor(screenIndex: Int): Color =
    ScreenProgressionColors[Math.floorMod(screenIndex, ScreenProgressionColors.size)]

/** The vivid progression accent for a habits screen index (cycles past the end). */
fun screenProgressionAccent(screenIndex: Int): Color =
    ScreenAccentColors[Math.floorMod(screenIndex, ScreenAccentColors.size)]

/**
 * Background colour for a habits screen (grid page): [base] shifted a barely
 * visible amount toward the screen's progression colour, so page 1 is faintly
 * red, page 2 faintly orange, page 3 faintly green, and so on.
 */
fun screenBackgroundTint(base: Color, screenIndex: Int): Color =
    lerp(base, screenProgressionColor(screenIndex), 0.26f)

/**
 * Returns the background color for a habit button based on today's effective points count.
 * [count] is already the divided/adjusted value from [buildHabit] — no further transformation
 * is applied here. The color tier maps directly to the displayed number.
 */
fun getHabitColor(habitName: String, count: Int): Color {
    return getHabitStyle(count).background
}

/**
 * Returns the full [HabitStyle] (background + optional border(s)) for a habit count.
 *
 *  Phase 1 — count 0–5:   Solid colour background (Red→Yellow), no border.
 *  Phase 2 — count 6:     Glass (near-white) background, no border.
 *  Phase 3 — count 7–12:  Glass background + single border cycling through the
 *                         6 vivid colours (Red→Orange→Green→Blue→Pink→Yellow).
 *  Phase 4 — count 13–48: Glass background + **double border**. The outer ring
 *                         cycles slowly (every 6 counts) and the inner ring cycles
 *                         quickly (every count), giving 6 × 6 = 36 distinct
 *                         combinations. Each outer cycle starts with a "double-X"
 *                         (outer and inner are the same colour, separated by a thin
 *                         black line) then the inner progresses through the remaining
 *                         colours. Count 49+ caps at the last combination
 *                         (Yellow outer + Yellow inner).
 *
 *  Total distinct tiers: 49 (0–48).
 */
/**
 * Style for inverted-binary habits (e.g. coffee tracking). The colour is driven
 * by the RAW count, not points, and mirrors the point outcome: orange on clean
 * days (0 occurrences → +1 point earned) and red on days the habit was done
 * (≥ 1 occurrence → no point, streak broken).
 */
fun getInvertedBinaryStyle(rawCount: Int): HabitStyle =
    HabitStyle(background = if (rawCount > 0) ColorRed else ColorOrange)

fun getHabitStyle(count: Int): HabitStyle {
    return when {
        count <= 5 -> HabitStyle(
            background = when (count) {
                0 -> ColorRed
                1 -> ColorOrange
                2 -> ColorGreen
                3 -> ColorBlue
                4 -> ColorPink
                else -> ColorYellow
            }
        )
        count == 6 -> HabitStyle(background = ColorGlass)
        count <= 12 -> {
            // Phase 3: Glass bg + single border cycling through 6 vivid colours
            val borderIndex = count - 7
            HabitStyle(background = ColorGlass, borderColor = vividBorderColors[borderIndex])
        }
        else -> {
            // Phase 4: Glass bg + double border.
            // 6 outer × 6 inner = 36 combinations (count 13–48), capped at 48.
            val doubleIndex = (count - 13).coerceAtMost(35)
            val outer = vividBorderColors[doubleIndex / 6]
            val inner = vividBorderColors[doubleIndex % 6]
            HabitStyle(
                background = ColorGlass,
                outerBorderColor = outer,
                innerBorderColor = inner
            )
        }
    }
}

/**
 * All available icon names (without .png extension), sorted alphabetically.
 * These correspond to drawable resources in res/drawable/.
 */
val ALL_ICON_NAMES: List<String> = listOf(
    "a_media21_arrow_back",
    "a_media22_arrow_forward1",
    "a_media23_arrows_seek_back",
    "a_media24_arrows_seek_forward",
    "a_media25_arrows_skip_back",
    "a_media26_arrows_skip_forward",
    "a_media27_pause_sign",
    "a_media28_stop",
    "a_media291_volume1",
    "a_media292_minus3",
    "a_media292_speaker_volume_right",
    "a_media293_speaker_volume_left",
    "a_media29_record",
    "a_media31_back",
    "a_media32_forward",
    "a_media33_down",
    "a_media34_up",
    "a_media35_add",
    "a_media36_delete",
    "airplane1",
    "airplane4",
    "airplane7_s",
    "ambulance2",
    "anchor6_sc48",
    "animal_antz",
    "animal_butterfly5_sc48",
    "animal_cat_print",
    "animal_crocodile_sc43",
    "animal_duck4",
    "animal_lizard1",
    "animal_mouse1",
    "animal_snail",
    "animal_spider",
    "arrow_styled_right",
    "arrows_rotated",
    "at_sign",
    "baby",
    "bag_paper1",
    "bag_paper3",
    "basket",
    "battery1",
    "bicycle",
    "binocular",
    "box1",
    "briefcase",
    "brush_paint55",
    "brush_paint57_sc52",
    "brush_painting_sc43",
    "cabinet",
    "calculator",
    "calendar",
    "camera",
    "camera1_sc49",
    "car12",
    "car9_sc44",
    "car_gauge3",
    "car_gears",
    "cart2",
    "cart_arrow",
    "cart_solid",
    "cd_load",
    "cd_refresh",
    "cd_sc52",
    "charcoal_cart",
    "charts1_sc1",
    "chest",
    "clipboard1",
    "clock1",
    "clock3",
    "clock4",
    "clock5_sc44",
    "clock7_sc43",
    "compass2",
    "compass4",
    "computer_desktop1",
    "computer_keyboard",
    "computer_laptop2",
    "computer_monitor",
    "computer_mouse",
    "computer_mouse2",
    "computer_server2",
    "computer_usb_drive_sc7",
    "copyright",
    "creditcard2",
    "currency_british_pound_sc35",
    "currency_cent_sc35",
    "currency_euro3",
    "currency_japanese_yen2_sc35",
    "cursor",
    "diamond5_sc27",
    "disc",
    "diskette4",
    "diskette_save",
    "document",
    "document1",
    "document3",
    "document4",
    "document5",
    "document6",
    "document9",
    "dollar",
    "electrical_plug1",
    "envelope1",
    "envelope5",
    "eye6",
    "fax",
    "fishbowl",
    "flower17",
    "folder",
    "folder1",
    "folder2_sc1",
    "foot_left_ps",
    "foot_steps",
    "gas_none_sc49",
    "gas_station4_sc49",
    "gear1",
    "gear3",
    "gear4",
    "gear8",
    "gear_c_sc44",
    "gears1_sc44",
    "gears_sc37",
    "globe",
    "hand22_sc48",
    "hat2_sc44",
    "head_set",
    "headset3",
    "helicopter4",
    "home5",
    "home6",
    "home7",
    "horn_sc48",
    "hourglass",
    "hourglass2",
    "icon_002",
    "icon_003",
    "information4_sc49",
    "ipod1",
    "ipod2",
    "key11_sc48",
    "key9",
    "key_hole_sc48",
    "keys_sc43",
    "ladder1_sc48",
    "lamp_aladin",
    "last_arrow_down",
    "last_arrow_left",
    "last_arrow_right",
    "leaf3",
    "letter_ii",
    "letter_nn",
    "light_bulb",
    "light_bulb2_sc52",
    "light_off",
    "lightning2_sc48",
    "lock3",
    "lock4",
    "lock5",
    "lock6_sc48",
    "lock_heart",
    "logo_superman_sc37",
    "loud_speaker",
    "loud_speaker1_ps",
    "magic_wand",
    "magnet",
    "magnify_zoom",
    "magnify_zoom_out",
    "magnifying_glass_ps",
    "mail",
    "mailbox",
    "mailbox1",
    "media2_arrow_down",
    "media2_arrow_up",
    "microscope",
    "moon",
    "music_accordian",
    "music_clarinet",
    "music_cleft",
    "music_drum1_sc44",
    "music_eighth_note",
    "music_eighth_notes",
    "music_guitar",
    "music_guitar1",
    "music_harp2",
    "music_microphone",
    "music_off_ps",
    "music_on_ps",
    "music_piano2_sc52",
    "music_piano_keys",
    "music_sax4",
    "music_sixteenth_note",
    "music_speaker",
    "music_tamborine",
    "music_trumpet1",
    "music_tuba",
    "music_violin",
    "notepad",
    "number_sign",
    "oil_well",
    "paperclip",
    "paperclip2",
    "pen1",
    "pen6_ps",
    "pen_crayon",
    "pencil1",
    "pencil7_sc49",
    "people_couple_sc44",
    "percent_sign",
    "phone",
    "phone1",
    "phone_cell",
    "phone_cell2",
    "phone_clear",
    "phone_solid",
    "picture_frame1_sc52",
    "police_car",
    "power_button4",
    "printer",
    "race_car",
    "raindrop2",
    "registered_mark1",
    "robot",
    "robot1",
    "satellite_dish_sc43",
    "scissors",
    "ship2",
    "ship_sc36",
    "ship_wheel1",
    "signature1",
    "snowflake3_sc37",
    "space_satelite",
    "spaceship",
    "spaceship2_sc43",
    "spiderweb2",
    "star_trek_sc43",
    "starburst",
    "tag",
    "tape_reel1",
    "tape_reel2",
    "text_size",
    "thumbs_down",
    "thumbs_down1",
    "thumbs_up1",
    "thumb_tack_ps",
    "tool",
    "tool_axe1_sc48",
    "tool_cutter1_sc44",
    "tool_hammer4_sc44",
    "tool_screw_sc44",
    "tool_shovel2_sc44",
    "tool_sword_sc48",
    "tool_wheelbarrow1_sc32",
    "tool_wrench8_sc44",
    "toolset_sc44",
    "trademark_ps",
    "train8_sc43",
    "train9_sc43",
    "trashcan",
    "trashcan3",
    "tree_palm4",
    "triangle_clear_up",
    "two_directions_left_right",
    "wall",
    "wand1_sc43",
    "webcam",
    "wireless",
    "www_search_ps",
    "x_solid"
)

/** Maps icon name (no extension) to its drawable resource ID. */
val ICON_NAME_TO_RES: Map<String, Int> = mapOf(
    "a_media21_arrow_back"           to R.drawable.a_media21_arrow_back,
    "a_media22_arrow_forward1"       to R.drawable.a_media22_arrow_forward1,
    "a_media23_arrows_seek_back"     to R.drawable.a_media23_arrows_seek_back,
    "a_media24_arrows_seek_forward"  to R.drawable.a_media24_arrows_seek_forward,
    "a_media25_arrows_skip_back"     to R.drawable.a_media25_arrows_skip_back,
    "a_media26_arrows_skip_forward"  to R.drawable.a_media26_arrows_skip_forward,
    "a_media27_pause_sign"           to R.drawable.a_media27_pause_sign,
    "a_media28_stop"                 to R.drawable.a_media28_stop,
    "a_media291_volume1"             to R.drawable.a_media291_volume1,
    "a_media292_minus3"              to R.drawable.a_media292_minus3,
    "a_media292_speaker_volume_right" to R.drawable.a_media292_speaker_volume_right,
    "a_media293_speaker_volume_left" to R.drawable.a_media293_speaker_volume_left,
    "a_media29_record"               to R.drawable.a_media29_record,
    "a_media31_back"                 to R.drawable.a_media31_back,
    "a_media32_forward"              to R.drawable.a_media32_forward,
    "a_media33_down"                 to R.drawable.a_media33_down,
    "a_media34_up"                   to R.drawable.a_media34_up,
    "a_media35_add"                  to R.drawable.a_media35_add,
    "a_media36_delete"               to R.drawable.a_media36_delete,
    "airplane1"                      to R.drawable.airplane1,
    "airplane4"                      to R.drawable.airplane4,
    "airplane7_s"                    to R.drawable.airplane7_s,
    "ambulance2"                     to R.drawable.ambulance2,
    "anchor6_sc48"                   to R.drawable.anchor6_sc48,
    "animal_antz"                    to R.drawable.animal_antz,
    "animal_butterfly5_sc48"         to R.drawable.animal_butterfly5_sc48,
    "animal_cat_print"               to R.drawable.animal_cat_print,
    "animal_crocodile_sc43"          to R.drawable.animal_crocodile_sc43,
    "animal_duck4"                   to R.drawable.animal_duck4,
    "animal_lizard1"                 to R.drawable.animal_lizard1,
    "animal_mouse1"                  to R.drawable.animal_mouse1,
    "animal_snail"                   to R.drawable.animal_snail,
    "animal_spider"                  to R.drawable.animal_spider,
    "arrow_styled_right"             to R.drawable.arrow_styled_right,
    "arrows_rotated"                 to R.drawable.arrows_rotated,
    "at_sign"                        to R.drawable.at_sign,
    "baby"                           to R.drawable.baby,
    "bag_paper1"                     to R.drawable.bag_paper1,
    "bag_paper3"                     to R.drawable.bag_paper3,
    "basket"                         to R.drawable.basket,
    "battery1"                       to R.drawable.battery1,
    "bicycle"                        to R.drawable.bicycle,
    "binocular"                      to R.drawable.binocular,
    "box1"                           to R.drawable.box1,
    "briefcase"                      to R.drawable.briefcase,
    "brush_paint55"                  to R.drawable.brush_paint55,
    "brush_paint57_sc52"             to R.drawable.brush_paint57_sc52,
    "brush_painting_sc43"            to R.drawable.brush_painting_sc43,
    "cabinet"                        to R.drawable.cabinet,
    "calculator"                     to R.drawable.calculator,
    "calendar"                       to R.drawable.calendar,
    "camera"                         to R.drawable.camera,
    "camera1_sc49"                   to R.drawable.camera1_sc49,
    "car12"                          to R.drawable.car12,
    "car9_sc44"                      to R.drawable.car9_sc44,
    "car_gauge3"                     to R.drawable.car_gauge3,
    "car_gears"                      to R.drawable.car_gears,
    "cart2"                          to R.drawable.cart2,
    "cart_arrow"                     to R.drawable.cart_arrow,
    "cart_solid"                     to R.drawable.cart_solid,
    "cd_load"                        to R.drawable.cd_load,
    "cd_refresh"                     to R.drawable.cd_refresh,
    "cd_sc52"                        to R.drawable.cd_sc52,
    "charcoal_cart"                  to R.drawable.charcoal_cart,
    "charts1_sc1"                    to R.drawable.charts1_sc1,
    "chest"                          to R.drawable.chest,
    "clipboard1"                     to R.drawable.clipboard1,
    "clock1"                         to R.drawable.clock1,
    "clock3"                         to R.drawable.clock3,
    "clock4"                         to R.drawable.clock4,
    "clock5_sc44"                    to R.drawable.clock5_sc44,
    "clock7_sc43"                    to R.drawable.clock7_sc43,
    "compass2"                       to R.drawable.compass2,
    "compass4"                       to R.drawable.compass4,
    "computer_desktop1"              to R.drawable.computer_desktop1,
    "computer_keyboard"              to R.drawable.computer_keyboard,
    "computer_laptop2"               to R.drawable.computer_laptop2,
    "computer_monitor"               to R.drawable.computer_monitor,
    "computer_mouse"                 to R.drawable.computer_mouse,
    "computer_mouse2"                to R.drawable.computer_mouse2,
    "computer_server2"               to R.drawable.computer_server2,
    "computer_usb_drive_sc7"         to R.drawable.computer_usb_drive_sc7,
    "copyright"                      to R.drawable.copyright,
    "creditcard2"                    to R.drawable.creditcard2,
    "currency_british_pound_sc35"    to R.drawable.currency_british_pound_sc35,
    "currency_cent_sc35"             to R.drawable.currency_cent_sc35,
    "currency_euro3"                 to R.drawable.currency_euro3,
    "currency_japanese_yen2_sc35"    to R.drawable.currency_japanese_yen2_sc35,
    "cursor"                         to R.drawable.cursor,
    "diamond5_sc27"                  to R.drawable.diamond5_sc27,
    "disc"                           to R.drawable.disc,
    "diskette4"                      to R.drawable.diskette4,
    "diskette_save"                  to R.drawable.diskette_save,
    "document"                       to R.drawable.document,
    "document1"                      to R.drawable.document1,
    "document3"                      to R.drawable.document3,
    "document4"                      to R.drawable.document4,
    "document5"                      to R.drawable.document5,
    "document6"                      to R.drawable.document6,
    "document9"                      to R.drawable.document9,
    "dollar"                         to R.drawable.dollar,
    "electrical_plug1"               to R.drawable.electrical_plug1,
    "envelope1"                      to R.drawable.envelope1,
    "envelope5"                      to R.drawable.envelope5,
    "eye6"                           to R.drawable.eye6,
    "fax"                            to R.drawable.fax,
    "fishbowl"                       to R.drawable.fishbowl,
    "flower17"                       to R.drawable.flower17,
    "folder"                         to R.drawable.folder,
    "folder1"                        to R.drawable.folder1,
    "folder2_sc1"                    to R.drawable.folder2_sc1,
    "foot_left_ps"                   to R.drawable.foot_left_ps,
    "foot_steps"                     to R.drawable.foot_steps,
    "gas_none_sc49"                  to R.drawable.gas_none_sc49,
    "gas_station4_sc49"              to R.drawable.gas_station4_sc49,
    "gear1"                          to R.drawable.gear1,
    "gear3"                          to R.drawable.gear3,
    "gear4"                          to R.drawable.gear4,
    "gear8"                          to R.drawable.gear8,
    "gear_c_sc44"                    to R.drawable.gear_c_sc44,
    "gears1_sc44"                    to R.drawable.gears1_sc44,
    "gears_sc37"                     to R.drawable.gears_sc37,
    "globe"                          to R.drawable.globe,
    "hand22_sc48"                    to R.drawable.hand22_sc48,
    "hat2_sc44"                      to R.drawable.hat2_sc44,
    "head_set"                       to R.drawable.head_set,
    "headset3"                       to R.drawable.headset3,
    "helicopter4"                    to R.drawable.helicopter4,
    "home5"                          to R.drawable.home5,
    "home6"                          to R.drawable.home6,
    "home7"                          to R.drawable.home7,
    "horn_sc48"                      to R.drawable.horn_sc48,
    "hourglass"                      to R.drawable.hourglass,
    "hourglass2"                     to R.drawable.hourglass2,
    "icon_002"                       to R.drawable.icon_002,
    "icon_003"                       to R.drawable.icon_003,
    "information4_sc49"              to R.drawable.information4_sc49,
    "ipod1"                          to R.drawable.ipod1,
    "ipod2"                          to R.drawable.ipod2,
    "key11_sc48"                     to R.drawable.key11_sc48,
    "key9"                           to R.drawable.key9,
    "key_hole_sc48"                  to R.drawable.key_hole_sc48,
    "keys_sc43"                      to R.drawable.keys_sc43,
    "ladder1_sc48"                   to R.drawable.ladder1_sc48,
    "lamp_aladin"                    to R.drawable.lamp_aladin,
    "last_arrow_down"                to R.drawable.last_arrow_down,
    "last_arrow_left"                to R.drawable.last_arrow_left,
    "last_arrow_right"               to R.drawable.last_arrow_right,
    "leaf3"                          to R.drawable.leaf3,
    "letter_ii"                      to R.drawable.letter_ii,
    "letter_nn"                      to R.drawable.letter_nn,
    "light_bulb"                     to R.drawable.light_bulb,
    "light_bulb2_sc52"               to R.drawable.light_bulb2_sc52,
    "light_off"                      to R.drawable.light_off,
    "lightning2_sc48"                to R.drawable.lightning2_sc48,
    "lock3"                          to R.drawable.lock3,
    "lock4"                          to R.drawable.lock4,
    "lock5"                          to R.drawable.lock5,
    "lock6_sc48"                     to R.drawable.lock6_sc48,
    "lock_heart"                     to R.drawable.lock_heart,
    "logo_superman_sc37"             to R.drawable.logo_superman_sc37,
    "loud_speaker"                   to R.drawable.loud_speaker,
    "loud_speaker1_ps"               to R.drawable.loud_speaker1_ps,
    "magic_wand"                     to R.drawable.magic_wand,
    "magnet"                         to R.drawable.magnet,
    "magnify_zoom"                   to R.drawable.magnify_zoom,
    "magnify_zoom_out"               to R.drawable.magnify_zoom_out,
    "magnifying_glass_ps"            to R.drawable.magnifying_glass_ps,
    "mail"                           to R.drawable.mail,
    "mailbox"                        to R.drawable.mailbox,
    "mailbox1"                       to R.drawable.mailbox1,
    "media2_arrow_down"              to R.drawable.media2_arrow_down,
    "media2_arrow_up"                to R.drawable.media2_arrow_up,
    "microscope"                     to R.drawable.microscope,
    "moon"                           to R.drawable.moon,
    "music_accordian"                to R.drawable.music_accordian,
    "music_clarinet"                 to R.drawable.music_clarinet,
    "music_cleft"                    to R.drawable.music_cleft,
    "music_drum1_sc44"               to R.drawable.music_drum1_sc44,
    "music_eighth_note"              to R.drawable.music_eighth_note,
    "music_eighth_notes"             to R.drawable.music_eighth_notes,
    "music_guitar"                   to R.drawable.music_guitar,
    "music_guitar1"                  to R.drawable.music_guitar1,
    "music_harp2"                    to R.drawable.music_harp2,
    "music_microphone"               to R.drawable.music_microphone,
    "music_off_ps"                   to R.drawable.music_off_ps,
    "music_on_ps"                    to R.drawable.music_on_ps,
    "music_piano2_sc52"              to R.drawable.music_piano2_sc52,
    "music_piano_keys"               to R.drawable.music_piano_keys,
    "music_sax4"                     to R.drawable.music_sax4,
    "music_sixteenth_note"           to R.drawable.music_sixteenth_note,
    "music_speaker"                  to R.drawable.music_speaker,
    "music_tamborine"                to R.drawable.music_tamborine,
    "music_trumpet1"                 to R.drawable.music_trumpet1,
    "music_tuba"                     to R.drawable.music_tuba,
    "music_violin"                   to R.drawable.music_violin,
    "notepad"                        to R.drawable.notepad,
    "number_sign"                    to R.drawable.number_sign,
    "oil_well"                       to R.drawable.oil_well,
    "paperclip"                      to R.drawable.paperclip,
    "paperclip2"                     to R.drawable.paperclip2,
    "pen1"                           to R.drawable.pen1,
    "pen6_ps"                        to R.drawable.pen6_ps,
    "pen_crayon"                     to R.drawable.pen_crayon,
    "pencil1"                        to R.drawable.pencil1,
    "pencil7_sc49"                   to R.drawable.pencil7_sc49,
    "people_couple_sc44"             to R.drawable.people_couple_sc44,
    "percent_sign"                   to R.drawable.percent_sign,
    "phone"                          to R.drawable.phone,
    "phone1"                         to R.drawable.phone1,
    "phone_cell"                     to R.drawable.phone_cell,
    "phone_cell2"                    to R.drawable.phone_cell2,
    "phone_clear"                    to R.drawable.phone_clear,
    "phone_solid"                    to R.drawable.phone_solid,
    "picture_frame1_sc52"            to R.drawable.picture_frame1_sc52,
    "police_car"                     to R.drawable.police_car,
    "power_button4"                  to R.drawable.power_button4,
    "printer"                        to R.drawable.printer,
    "race_car"                       to R.drawable.race_car,
    "raindrop2"                      to R.drawable.raindrop2,
    "registered_mark1"               to R.drawable.registered_mark1,
    "robot"                          to R.drawable.robot,
    "robot1"                         to R.drawable.robot1,
    "satellite_dish_sc43"            to R.drawable.satellite_dish_sc43,
    "scissors"                       to R.drawable.scissors,
    "ship2"                          to R.drawable.ship2,
    "ship_sc36"                      to R.drawable.ship_sc36,
    "ship_wheel1"                    to R.drawable.ship_wheel1,
    "signature1"                     to R.drawable.signature1,
    "snowflake3_sc37"                to R.drawable.snowflake3_sc37,
    "space_satelite"                 to R.drawable.space_satelite,
    "spaceship"                      to R.drawable.spaceship,
    "spaceship2_sc43"                to R.drawable.spaceship2_sc43,
    "spiderweb2"                     to R.drawable.spiderweb2,
    "star_trek_sc43"                 to R.drawable.star_trek_sc43,
    "starburst"                      to R.drawable.starburst,
    "tag"                            to R.drawable.tag,
    "tape_reel1"                     to R.drawable.tape_reel1,
    "tape_reel2"                     to R.drawable.tape_reel2,
    "text_size"                      to R.drawable.text_size,
    "thumbs_down"                    to R.drawable.thumbs_down,
    "thumbs_down1"                   to R.drawable.thumbs_down1,
    "thumbs_up1"                     to R.drawable.thumbs_up1,
    "thumb_tack_ps"                  to R.drawable.thumb_tack_ps,
    "tool"                           to R.drawable.tool,
    "tool_axe1_sc48"                 to R.drawable.tool_axe1_sc48,
    "tool_cutter1_sc44"              to R.drawable.tool_cutter1_sc44,
    "tool_hammer4_sc44"              to R.drawable.tool_hammer4_sc44,
    "tool_screw_sc44"                to R.drawable.tool_screw_sc44,
    "tool_shovel2_sc44"              to R.drawable.tool_shovel2_sc44,
    "tool_sword_sc48"                to R.drawable.tool_sword_sc48,
    "tool_wheelbarrow1_sc32"         to R.drawable.tool_wheelbarrow1_sc32,
    "tool_wrench8_sc44"              to R.drawable.tool_wrench8_sc44,
    "toolset_sc44"                   to R.drawable.toolset_sc44,
    "trademark_ps"                   to R.drawable.trademark_ps,
    "train8_sc43"                    to R.drawable.train8_sc43,
    "train9_sc43"                    to R.drawable.train9_sc43,
    "trashcan"                       to R.drawable.trashcan,
    "trashcan3"                      to R.drawable.trashcan3,
    "tree_palm4"                     to R.drawable.tree_palm4,
    "triangle_clear_up"              to R.drawable.triangle_clear_up,
    "two_directions_left_right"      to R.drawable.two_directions_left_right,
    "wall"                           to R.drawable.wall,
    "wand1_sc43"                     to R.drawable.wand1_sc43,
    "webcam"                         to R.drawable.webcam,
    "wireless"                       to R.drawable.wireless,
    "www_search_ps"                  to R.drawable.www_search_ps,
    "x_solid"                        to R.drawable.x_solid
)

/**
 * Maps each habit name to its default drawable resource ID.
 * Derived from IconFinder.py in py_habits_widget.
 */
val HABIT_ICON: Map<String, Int> = mapOf(
    "Article read"               to R.drawable.document,
    "Flossed"                    to R.drawable.tool_cutter1_sc44,
    "Programming sessions"       to R.drawable.computer_keyboard,
    "Kind stranger"              to R.drawable.baby,
    "Meditations"                to R.drawable.electrical_plug1,
    "Juggling tech sessions"     to R.drawable.gears_sc37,
    "Unusual experience"         to R.drawable.fishbowl,
    "AI tool"                    to R.drawable.magic_wand,
    "Broke record"               to R.drawable.disc,
    "Podcast finished"           to R.drawable.headset3,
    "Apnea walked"               to R.drawable.music_trumpet1,
    "Juggling record broke"      to R.drawable.hand22_sc48,
    "Sleep watch"                to R.drawable.clock5_sc44,
    "Early phone"                to R.drawable.ipod1,
    "Drew"                       to R.drawable.pen1,
    "Writing sessions"           to R.drawable.pencil1,
    "Cold Shower Widget"         to R.drawable.snowflake3_sc37,
    "Music listen"               to R.drawable.music_eighth_notes,
    "Anki created"               to R.drawable.toolset_sc44,
    "Good posture"               to R.drawable.robot1,
    "Educational video watched"  to R.drawable.computer_monitor,
    "Health learned"             to R.drawable.raindrop2,
    "Language studied"           to R.drawable.globe,
    "Janki used"                 to R.drawable.binocular,
    "Apnea practiced"            to R.drawable.music_tuba,
    "Anki mydis done"            to R.drawable.microscope,
    "Situps"                     to R.drawable.animal_mouse1,
    "Question asked"             to R.drawable.people_couple_sc44,
    "UC post"                    to R.drawable.wireless,
    "Dream acted"                to R.drawable.ship_sc36,
    "Pushups"                    to R.drawable.animal_lizard1,
    "Todos done"                 to R.drawable.clipboard1,
    "Apnea spb"                  to R.drawable.logo_superman_sc37,
    "Apnea apb"                  to R.drawable.music_microphone,
    "Cardio sessions"            to R.drawable.bicycle,
    "Squats"                     to R.drawable.animal_duck4,
    "Fun juggle"                 to R.drawable.a_media29_record,
    "Took pills"                 to R.drawable.car_gauge3,
    "Book read"                  to R.drawable.registered_mark1,
    "Juggle goal"                to R.drawable.ladder1_sc48,
    "Filmed juggle"              to R.drawable.camera,
    "Inspired juggle"            to R.drawable.information4_sc49,
    "Read academic"              to R.drawable.charts1_sc1,
    "Lung stretch"               to R.drawable.two_directions_left_right,
    "Drm Review"                 to R.drawable.anchor6_sc48,
    "Unique juggle"              to R.drawable.animal_cat_print,
    "Create juggle"              to R.drawable.animal_butterfly5_sc48,
    "Song juggle"                to R.drawable.music_cleft,
    "Memory practice"            to R.drawable.diskette4,
    "Grumpy blocker"             to R.drawable.lock_heart,
    "Lucidity trained"           to R.drawable.train8_sc43,
    "HIT"                        to R.drawable.animal_crocodile_sc43,
    "Some anki"                  to R.drawable.paperclip,
    "Move juggle"                to R.drawable.arrows_rotated,
    "Watch juggle"               to R.drawable.magnifying_glass_ps,
    "Fresh air"                  to R.drawable.tree_palm4,
    "Talk stranger"              to R.drawable.magnet,
    "Balanced"                   to R.drawable.letter_ii,
    "Fasted"                     to R.drawable.eye6,
    "Magic practiced"            to R.drawable.key11_sc48,
    "Magic performed"            to R.drawable.lock6_sc48,
    "Sweat"                      to R.drawable.hourglass,
    "Free"                       to R.drawable.foot_left_ps,
    "Juggle run"                 to R.drawable.robot,
    "Juggle lights"              to R.drawable.flower17,
    "Joggle"                     to R.drawable.police_car,
    "Blind juggle"               to R.drawable.moon,
    "Juggling Balls Carry"       to R.drawable.copyright,
    "Juggling Others Learn"      to R.drawable.www_search_ps,
    "No Coffee"                  to R.drawable.gas_none_sc49,
    "Tracked Sleep"              to R.drawable.helicopter4,
    "Rabbit Hole"                to R.drawable.chest,
    "Speak AI"                   to R.drawable.loud_speaker,
    "Fiction Book Intake"        to R.drawable.document4,
    "Fiction Video Intake"       to R.drawable.a_media22_arrow_forward1,
    "Communication Improved"     to R.drawable.text_size,
    "Unusually Kind"             to R.drawable.thumbs_up1,
    "Most Collisions"            to R.drawable.compass2,
    "Chess"                      to R.drawable.key_hole_sc48
)

/**
 * Returns the drawable resource ID for a habit, checking custom icon overrides first,
 * then falling back to the default HABIT_ICON map.
 *
 * @param habitName the habit name
 * @param customIconOverrides map of habit name → icon name (from settings)
 */
fun getHabitIconRes(habitName: String, customIconOverrides: Map<String, String> = emptyMap()): Int? {
    val customIconName = customIconOverrides[habitName]
    if (customIconName != null) {
        val res = ICON_NAME_TO_RES[customIconName]
        if (res != null) return res
    }
    return HABIT_ICON[habitName]
}

/** Reverse of [ICON_NAME_TO_RES]: drawable resource ID → icon name. */
private val RES_TO_ICON_NAME: Map<Int, String> =
    ICON_NAME_TO_RES.entries.associate { (name, res) -> res to name }

/**
 * Returns the icon NAME that [getHabitIconRes] would resolve for [habitName]:
 * the custom override when set, otherwise the [HABIT_ICON] default
 * reverse-mapped to its drawable name. Null when the habit has no icon at all.
 */
fun getHabitIconName(habitName: String, customIconOverrides: Map<String, String> = emptyMap()): String? {
    customIconOverrides[habitName]?.let { return it }
    return RES_TO_ICON_NAME[HABIT_ICON[habitName]]
}

/**
 * Produces the habitIcons override map for a habit renamed from [oldName] to
 * [newName] so the icon fully follows the rename:
 *
 *  - An existing custom override is re-keyed to [newName].
 *  - When the habit had NO override — its icon came from the hardcoded
 *    [HABIT_ICON] defaults, which are keyed by the ORIGINAL habit name — the
 *    currently-resolved default is materialised as an explicit override under
 *    [newName]. Without this, the renamed habit falls back to "no icon"
 *    because its new name no longer matches any hardcoded default.
 *  - A habit that never had an icon stays icon-less: any stale orphaned
 *    override left under [newName] (e.g. from a long-deleted habit) is
 *    dropped so the renamed habit's icon state is exactly what it was.
 *
 * The habit's effective icon ALWAYS wins over any orphaned entry already
 * sitting under [newName] — the DB-level rename refuses habit-name collisions,
 * so such entries can only be leftovers, never a live habit's override.
 */
fun renamedHabitIcons(
    oldName: String,
    newName: String,
    habitIcons: Map<String, String>
): Map<String, String> {
    val iconName = getHabitIconName(oldName, habitIcons)
    val result = habitIcons.toMutableMap()
    result.remove(oldName)
    if (iconName != null) {
        result[newName] = iconName
    } else {
        result.remove(newName)
    }
    return result
}
