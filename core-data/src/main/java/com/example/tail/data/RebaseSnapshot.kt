package com.example.tail.data

/**
 * Pure 3-way merge of a whole-file habits-DB snapshot onto the CURRENT disk
 * state, used by `HabitsRepository.persistDatabaseRebased`.
 *
 * The scenario: a background sync read the file into [base], edited it into
 * [desired], and is about to overwrite the file — but in the meantime an
 * increment (widget tap, bubble timer, IPC broadcast) landed on [disk]. A
 * plain overwrite would erase that increment; this merge keeps it.
 *
 * Rule per storage key:
 *  - key in [forceHabits]            → [desired] owns the whole key
 *    (authoritative SET-style writers that legitimately lower values:
 *    resets, absolute-value syncs, migrations);
 *  - [desired] == [base] for the key → the caller did not touch it, so the
 *    disk value wins (it may carry newer increments);
 *  - otherwise                        → per-date: dates the caller CHANGED
 *    (desired differs from base) take its value; dates it did not change
 *    take the disk value. Deletions the caller made (date in base but not
 *    desired) are honoured.
 *
 * Fast path: untouched keys keep [disk]'s existing map instance, so the
 * merge cost is proportional to the keys the caller actually edited.
 *
 * A caveat that is accepted on purpose: if the caller changed a date AND an
 * increment moved that same date on disk, the caller's value wins. That is
 * correct for every SET-style sync (chess.com minutes, Garmin, GitHub are
 * authoritative for their own slots) and no count-increment path persists
 * whole snapshots — they all go through the verified RMW.
 */
fun rebaseSnapshot(
    base: HabitsDatabase,
    desired: HabitsDatabase,
    disk: HabitsDatabase,
    forceHabits: Set<String> = emptySet()
): HabitsDatabase {
    val out = HashMap<String, Map<String, Int>>(disk.size + 8)
    // Keys on disk that the caller's snapshot never had: brand new since it
    // read — keep them as they are.
    for ((k, v) in disk) if (!base.containsKey(k) && !desired.containsKey(k)) out[k] = v

    for ((key, desiredEntries) in desired) {
        val baseEntries = base[key]
        val diskEntries = disk[key]
        when {
            key in forceHabits -> out[key] = desiredEntries
            // Caller did not touch this key at all → disk is at least as new.
            desiredEntries == baseEntries -> out[key] = diskEntries ?: desiredEntries
            diskEntries == null -> out[key] = desiredEntries
            else -> {
                val merged = HashMap<String, Int>(maxOf(diskEntries.size, desiredEntries.size) * 2)
                // Start from disk (keeps dates added behind the caller's back)…
                merged.putAll(diskEntries)
                for ((date, want) in desiredEntries) {
                    val was = baseEntries?.get(date)
                    if (want != was) merged[date] = want          // caller changed it → caller wins
                    // else: untouched by caller → keep disk's (possibly newer) value
                }
                // Deletions the caller made explicitly.
                if (baseEntries != null) {
                    for (date in baseEntries.keys) if (!desiredEntries.containsKey(date)) merged.remove(date)
                }
                out[key] = merged.toSortedMap()
            }
        }
    }
    // Keys the caller deleted on purpose (present in base, absent in desired):
    // only honour that when disk still matches base (nobody wrote to it since).
    for ((key, baseEntries) in base) {
        if (!desired.containsKey(key) && disk[key] == baseEntries) out.remove(key)
    }
    return out
}
