package com.example.tail.ui

import androidx.compose.ui.graphics.Color
import com.example.tail.R
import com.example.tail.data.getDisplayValue

// 7 color tiers matching the desktop icon folders
val ColorRed    = Color(0xFFB71C1C)   // redgoldpainthd    — count 0
val ColorOrange = Color(0xFFE65100)   // orangewhitepearlhd — count 1
val ColorGreen  = Color(0xFF2E7D32)   // greenfloralhd      — count 2
val ColorBlue   = Color(0xFF1565C0)   // bluewhitepearlhd   — count 3
val ColorPink   = Color(0xFFAD1457)   // pinkorbhd          — count 4
val ColorYellow = Color(0xFFF9A825)   // yellowpainthd      — count 5
val ColorGlass  = Color(0xFF37474F)   // transparentglasshd — count 6+

/**
 * Returns the background color for a habit button based on today's count,
 * applying the same special adjustments as the desktop app.
 */
fun getHabitColor(habitName: String, rawCount: Int): Color {
    val displayValue = getDisplayValue(habitName, rawCount)
    return when (displayValue) {
        0    -> ColorRed
        1    -> ColorOrange
        2    -> ColorGreen
        3    -> ColorBlue
        4    -> ColorPink
        5    -> ColorYellow
        else -> ColorGlass
    }
}

/**
 * Maps each habit name to its drawable resource ID.
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
    "Launch Situps Widget"       to R.drawable.animal_mouse1,
    "Question asked"             to R.drawable.people_couple_sc44,
    "UC post"                    to R.drawable.wireless,
    "Dream acted"                to R.drawable.ship_sc36,
    "Launch Pushups Widget"      to R.drawable.animal_lizard1,
    "Todos done"                 to R.drawable.clipboard1,
    "Apnea spb"                  to R.drawable.logo_superman_sc37,
    "Apnea apb"                  to R.drawable.music_microphone,
    "Cardio sessions"            to R.drawable.bicycle,
    "Launch Squats Widget"       to R.drawable.animal_duck4,
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

fun getHabitIconRes(habitName: String): Int? = HABIT_ICON[habitName]
