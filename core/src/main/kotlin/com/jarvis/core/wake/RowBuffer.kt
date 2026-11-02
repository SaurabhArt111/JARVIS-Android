package com.jarvis.core.wake

/**
 * Fixed-width float rows where only the newest [maxRows] are kept: the same behaviour as the reference's
 * `np.vstack` followed by `buffer[-maxRows:]`, without copying the whole buffer on every append.
 * (Storage is twice [maxRows] and compacted when full, so appends are amortised O(rows appended).)
 */
class RowBuffer(val cols: Int, val maxRows: Int) {
    private val data = FloatArray(cols * maxRows * 2)
    private var start = 0
    var rows = 0
        private set

    fun clear() {
        start = 0
        rows = 0
    }

    fun fillOnes(count: Int) {
        clear()
        java.util.Arrays.fill(data, 0, cols * count, 1f)
        rows = count
    }

    /** Appends [count] rows taken from [src] (which holds at least count*cols floats). */
    fun append(src: FloatArray, count: Int, transform: ((Float) -> Float)? = null) {
        require(src.size >= count * cols) { "source too small" }
        var from = 0
        var n = count
        if (n > maxRows) { // only the newest maxRows can survive
            from = n - maxRows
            n = maxRows
        }
        if (start + rows + n > maxRows * 2) {
            System.arraycopy(data, start * cols, data, 0, rows * cols)
            start = 0
        }
        val dst = (start + rows) * cols
        val srcOff = from * cols
        if (transform == null) {
            System.arraycopy(src, srcOff, data, dst, n * cols)
        } else {
            for (i in 0 until n * cols) data[dst + i] = transform(src[srcOff + i])
        }
        rows += n
        if (rows > maxRows) {
            start += rows - maxRows
            rows = maxRows
        }
    }

    /** Copies rows [fromRow, fromRow+count) counted from the OLDEST kept row into [dest]. */
    fun copyRows(fromRow: Int, count: Int, dest: FloatArray, destOffset: Int = 0) {
        require(fromRow >= 0 && count >= 0 && fromRow + count <= rows) { "rows out of range: $fromRow+$count of $rows" }
        System.arraycopy(data, (start + fromRow) * cols, dest, destOffset, count * cols)
    }
}

/** Newest-[capacity]-samples ring of PCM16. */
class ShortRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var head = 0 // next write position
    var size = 0
        private set

    fun clear() {
        head = 0
        size = 0
    }

    fun push(src: ShortArray, offset: Int, length: Int) {
        var off = offset
        var len = length
        if (len >= capacity) { // only the newest samples fit
            off += len - capacity
            len = capacity
        }
        var written = 0
        while (written < len) {
            val chunk = minOf(len - written, capacity - head)
            System.arraycopy(src, off + written, data, head, chunk)
            head = (head + chunk) % capacity
            written += chunk
        }
        size = minOf(capacity, size + length)
    }

    /** Returns everything held (oldest first) and empties the ring. */
    fun drain(): ShortArray {
        val out = ShortArray(size)
        var pos = (head - size + capacity) % capacity
        for (i in 0 until size) {
            out[i] = data[pos]
            pos++
            if (pos == capacity) pos = 0
        }
        clear()
        return out
    }

    /** Copies the newest [wanted] samples (or fewer, if fewer are held) as floats; returns how many. */
    fun copyLastAsFloat(wanted: Int, dest: FloatArray): Int {
        val n = minOf(wanted, size)
        var pos = (head - n + capacity) % capacity
        for (i in 0 until n) {
            dest[i] = data[pos].toFloat()
            pos++
            if (pos == capacity) pos = 0
        }
        return n
    }
}
