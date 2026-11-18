package android.util

/**
 * JVM stand-in for the framework SparseArray, which the unit-test android.jar stubs out (every
 * call returns null). Media3's ChannelMixingAudioProcessor keeps its mixing matrices in one, so
 * without this a unit test can't configure [tech.capullo.audio.player.FifoFormat.stage]. Test
 * classes come before the stub jar on the unit-test classpath, so this class shadows it.
 */
class SparseArray<E> {
    private val values = HashMap<Int, E>()

    fun get(key: Int): E? = values[key]

    fun put(key: Int, value: E) {
        values[key] = value
    }
}
