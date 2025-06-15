@file:OptIn(ExperimentalForeignApi::class)
package com.monkopedia.frameworkled

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCValues
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.open
import kotlin.experimental.or
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.system.getTimeMillis
import kotlin.time.TimeSource
import kotlin.time.measureTime

val BRIGHTNESS_CMD = 0x00.toUByte()
val PATTERN_CMD = 0x01.toUByte()
val BOOTLOADER_CMD = 0x02.toUByte()
val SLEEP_CMD = 0x03.toUByte()
val ANIMATE_CMD = 0x04.toUByte()
val PANIC_CMD = 0x05.toUByte()
val DRAW_CMD = 0x06.toUByte()
val STAGE_GREY_COL_CMD = 0x07.toUByte()
val DrawGreyColBuffer = 0x08.toUByte()
val SetText = 0x09.toUByte()
val StartGame = 0x10.toUByte()
val GameControl = 0x11.toUByte()
val GameStatus = 0x12.toUByte()
val SetColor = 0x13.toUByte()
val DisplayOn = 0x14.toUByte()
val InvertScreen = 0x15.toUByte()
val SetPixelColumn = 0x16.toUByte()
val FlushFramebuffer = 0x17.toUByte()
val VERSION_CMD = 0x20.toUByte()

const val WIDTH = 9.toByte()
const val HEIGHT = 34.toByte()

val PATTERNS =
    arrayOf(
        "Custom",
        "Blank",
        "Full",
        "Checkerboard",
        "Double Checkerboard",
        "Every 2nd Row",
        "Every 3rd Row",
        "Every 2nd Col",
        "Every 3rd Col",
    )

fun main(args: Array<String>) {
    var timeSource = TimeSource.Monotonic.markNow()
    var lastSeconds = 0L
    var count = 0
    val serial1: Int = open("/dev/ttyACM0", O_RDWR)
    val serial2: Int = open("/dev/ttyACM1", O_RDWR)
    try {
        withoutIcanon(serial1) {
            withoutIcanon(serial2) {
                val display1 = Display(serial1)
                val display2 = Display(serial2)
                val displays = Displays(display1, display2)
                println("Left:")
                println(displays.left.checkFirmwareVersion())
                println("Right:")
                println(displays.right.checkFirmwareVersion())
                if (!args.getOrNull(0).isNullOrBlank()) {
                    while (true) {
                        val elapsedNow = timeSource.elapsedNow()
                        val offset = (elapsedNow.inWholeMilliseconds / 1000f)
                        if (elapsedNow.inWholeSeconds != lastSeconds) {
                            println("$count fps")
                            lastSeconds = elapsedNow.inWholeSeconds
                            count = 0
                        }
                        displays.left.drawPattern(args[0], offset)
                        displays.right.drawPattern(args[0], offset)
                        count++
                        if (offset > 34f) {
                            timeSource = TimeSource.Monotonic.markNow()
                        }
                    }
                }
            }
        }
    } finally {
        close(serial1)
        close(serial2)
    }
}

val firmwareVersionId = 0x20.toUByte()

class Display(
    val fd: Int,
) {
    var matrix: Array<UByteArray> = createArray(9, 34)

    fun write(bytes: UByteArray) {
        platform.posix.write(fd, bytes.toCValues(), bytes.size.convert())
    }

    fun read(i: Int): Pair<ByteArray, Boolean> {
        return memScoped {
            val refBuf = allocArray<ByteVar>(i)
            val amount = platform.posix.read(fd, refBuf, i.convert())

            refBuf.readBytes(i) to (amount.toInt() != i)
        }
    }

    fun drawPattern(
        pattern: String,
        offset:Float = 0f
    ) {
        val offsetInt = offset.toInt()
        val nextOffset = sqrt(offset - offsetInt)
        val currentOffset = sqrt(1f - (offset - offsetInt))
        val nextByte = (255 - (nextOffset * 255).roundToInt()).toUByte()
        val currentByte = (255 - (currentOffset * 255).roundToInt()).toUByte()
        for (col in 0 until WIDTH) {
            for (row in 0 until HEIGHT) {
                if (offsetInt == row) {
                    matrix[col][row] = currentByte
                } else if (offsetInt == row + 1) {
                    matrix[col][row] = nextByte
                } else {
                    matrix[col][row] = 0u
                }
//                if (pattern == "Blank") {
//                    matrix[row][col] = 1
//                } else if (pattern == "Full") {
//                    matrix[row][col] = 0
//                } else if (pattern == "Checkerboard") {
//                    if (row % 2 == 0) {
//                        matrix[row][col] = if (col.mod(2) == 0) 1 else 0
//                    } else {
//                        matrix[row][col] = if ((col + 1).mod(2) == 0) 1 else 0
//                    }
//                } else if (pattern == "Double Checkerboard") {
//                    if (row % 4 < 2) {
//                        matrix[row][col] = if ((col + 2).mod(4) < 2) 1 else 0
//                    } else {
//                        matrix[row][col] = if ((col).mod(4) < 2) 1 else 0
//                    }
//                } else if (pattern == "Every 2nd Row") {
//                    matrix[row][col] = if (row.mod(2) != 0) 1 else 0
//                } else if (pattern == "Every 3rd Row") {
//                    matrix[row][col] = if ((row + offset).mod(3) != 0) 1 else 0
//                } else if (pattern == "Every 2nd Col") {
//                    matrix[row][col] = if (col.mod(2) != 0) 1 else 0
//                } else if (pattern == "Every 3rd Col") {
//                    matrix[row][col] = if (col.mod(3) != 0) 1 else 0
//                }
            }
        }
        sendToDisplay()
    }

    fun clear() {
        matrix = createArray(34, 9)
        sendToDisplay()
    }

    fun setBrightness(brightness: Int) {
        command(BRIGHTNESS_CMD, brightness.toUByte())
    }

    fun command(
        id: UByte,
        vararg params: UByte,
    ) {
        val bytes = UByteArray(params.size + 3)
        bytes[0] = 0x32.toUByte()
        bytes[1] = 0xAC.toUByte()
        bytes[2] = id
        for (i in params.indices) {
            bytes[i + 3] = params[i]
        }

        write(bytes)
    }

    fun checkFirmwareVersion(): String {
        command(firmwareVersionId)

        val (bytes, done) = read(3)
        // Attention: Seems the variable name `value` cannot be changed!
        val response = bytes

        val major = response[0].toInt()
        val minor = (response[1].toInt() and 0xF0) shr 4
        val patch = response[1].toInt() and 0x0F
        val preRelease = response[2].toInt() == 1

        val fw = "Device FW Version: $major.$minor.$patch Pre-release: $preRelease"
        return fw
    }

    private fun Array<ByteArray>.prepareValsForDrawing(): ByteArray {
        val width = this[0].size
        val height = this.size

        val vals = ByteArray(39)

        for (col in 0 until width) {
            for (row in 0 until height) {
                val cell = this[row][col]
                if (cell == 0.toByte()) {
                    val i = col + row * width
                    vals[(i / 8)] = vals[(i / 8)] or (1 shl i.mod(8)).toByte()
                }
            }
        }
        return vals
    }

    fun wake(wake: Boolean) {
        command(0x03u, if (wake) 0u else 1u)
    }

    fun bootloader() {
        command(0x02u, 0.toUByte())
    }

    fun sendToDisplay() {
//        val vals = matrix.prepareValsForDrawing()
        for (i in 0 until 9) {
            command(STAGE_GREY_COL_CMD, i.toUByte(), *matrix[i])
        }
        command(DrawGreyColBuffer)
    }
}

class Displays(
    val left: Display,
    val right: Display,
)

fun createArray(length: Int): UByteArray = UByteArray(length)

fun createArray(
    width: Int,
    height: Int,
): Array<UByteArray> =
    Array(width) {
        createArray(height)
    }
