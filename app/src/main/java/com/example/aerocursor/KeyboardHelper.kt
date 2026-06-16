package com.example.aerocursor

object KeyboardHelper {
    /**
     * Maps a character to HID Keycode + Shift Modifier.
     * Simplified US-English mapping.
     */
    fun getHidKey(char: Char): Pair<Byte, Byte> {
        var shift: Byte = 0
        val code: Byte = when (char) {
            in 'a'..'z' -> (char - 'a' + 4).toByte()
            in 'A'..'Z' -> { shift = 2; (char - 'A' + 4).toByte() }
            in '1'..'9' -> (char - '1' + 30).toByte()
            '0' -> 39
            ' ' -> 44
            '\n' -> 40
            '\t' -> 43
            '.' -> 55
            ',' -> 54
            '/' -> 56
            ';' -> 51
            '\'' -> 52
            '[' -> 47
            ']' -> 48
            '\\' -> 49
            '-' -> 45
            '=' -> 46
            '!' -> { shift = 2; 30 }
            '@' -> { shift = 2; 31 }
            '#' -> { shift = 2; 32 }
            '$' -> { shift = 2; 33 }
            '%' -> { shift = 2; 34 }
            '^' -> { shift = 2; 35 }
            '&' -> { shift = 2; 36 }
            '*' -> { shift = 2; 37 }
            '(' -> { shift = 2; 38 }
            ')' -> { shift = 2; 39 }
            '_' -> { shift = 2; 45 }
            '+' -> { shift = 2; 46 }
            ':' -> { shift = 2; 51 }
            '\"' -> { shift = 2; 52 }
            '<' -> { shift = 2; 54 }
            '>' -> { shift = 2; 55 }
            '?' -> { shift = 2; 56 }
            '{' -> { shift = 2; 47 }
            '}' -> { shift = 2; 48 }
            '|' -> { shift = 2; 49 }
            else -> 0
        }
        return Pair(code, shift)
    }
}
