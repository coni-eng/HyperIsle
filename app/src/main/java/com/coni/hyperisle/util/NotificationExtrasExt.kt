package com.coni.hyperisle.util

import android.os.Bundle



/**
 * CharSequence-safe helpers for notification extras.
 * Avoids ClassCastException when extras contain SpannableString.
 */
fun Bundle.getStringCompat(key: String): String? = getCharSequence(key)?.toString()

fun Bundle.getStringCompatOrEmpty(key: String): String = getCharSequence(key)?.toString().orEmpty()
