package com.miruronative

import androidx.core.content.FileProvider

/** Dedicated provider type for update APKs and explicitly shared diagnostic snapshots. */
class AppFileProvider : FileProvider(R.xml.file_paths)
