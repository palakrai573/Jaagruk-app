package org.jaagruk.core.cert

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jaagruk.core.crypto.Ed25519
import org.jaagruk.core.crypto.Sha256
import org.jaagruk.core.tools.FixtureGenerator
import org.jaagruk.core.util.Hex
import org.junit.jupiter.api.Test

/**
 * Pins the canonical encoding to committed byte vectors.
 *
 * `backend/tests/test_canonical_parity.py` asserts the Python implementation against the very
 * same file. Together the two tests mean neither side can drift: a change to the Kotlin encoder
 * fails here, a change to the Python encoder fails there, and a deliberate format change requires
 * regenerating the fixtures and bumping [Attestation.FORMAT_VERSION].
 *
 * Without this pairing, a one-byte difference in field order would let the app keep issuing
 * certificates that the server silently rejects as forged.
 */
class AttestationVectorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val document: JsonObject by lazy {
        val stream = requireNotNull(
            javaClass.getResourceAsStream("/fixtures/attestation_vectors.json"),
        ) {
            "attestation_vectors.json is missing. Run: gradlew :core:generateFixtures"
        }
        json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val vectors: List<JsonObject>
        get() = document["vectors"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.number(key: String): Long = this[key]!!.jsonPrimitive.long

    private fun JsonObject.bytes(key: String): ByteArray = Hex.decode(text(key))

    private fun JsonObject.toAttestation(): Attestation = Attestation(
        siteId = text("siteId"),
        seq = number("seq"),
        workerIdHash = bytes("workerIdHashHex"),
        moduleCode = number("moduleCode").toInt(),
        scorePermille = number("scorePermille").toInt(),
        medianLatencyMs = number("medianLatencyMs"),
        outcomeFlags = OutcomeFlags.fromBits(number("outcomeFlags").toInt()),
        issuedAtEpochMin = number("issuedAtEpochMin"),
        prevRecordHash = bytes("prevRecordHashHex"),
    )

    @Test
    fun `fixture document has the expected shape`() {
        assertThat(document.number("formatVersion").toInt())
            .isEqualTo(Attestation.FORMAT_VERSION)
        assertThat(document.text("magic")).isEqualTo(AttestationCodec.MAGIC)
        assertThat(document.text("qrTextPrefix")).isEqualTo(QrCodec.TEXT_PREFIX)
        assertThat(vectors).isNotEmpty()
        assertThat(vectors.map { it.text("name") }).containsNoDuplicates()
    }

    @Test
    fun `fixture key material matches`() {
        val privateKey = document.bytes("sitePrivateKeyHex")
        val publicKey = document.bytes("sitePublicKeyHex")
        assertThat(Ed25519.publicKeyFromPrivate(privateKey)).isEqualTo(publicKey)
    }

    @Test
    fun `kotlin reproduces every canonical byte string`() {
        val privateKey = document.bytes("sitePrivateKeyHex")

        vectors.forEach { vector ->
            val name = vector.text("name")
            val attestation = vector.toAttestation()

            val canonical = AttestationCodec.canonicalBytes(attestation)
            assertThat(Hex.encode(canonical)).isEqualTo(vector.text("canonicalHex"))

            val signature = Ed25519.sign(privateKey, canonical)
            assertThat(Hex.encode(signature)).isEqualTo(vector.text("signatureHex"))

            val recordHash = AttestationCodec.recordHash(canonical, signature)
            assertThat(Hex.encode(recordHash)).isEqualTo(vector.text("recordHashHex"))

            val signed = SignedAttestation(attestation, signature, recordHash)
            assertThat(QrCodec.encode(signed)).isEqualTo(vector.text("qrText"))

            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `kotlin decodes every fixture qr code back to the same record`() {
        vectors.forEach { vector ->
            val decoded = QrCodec.decode(vector.text("qrText"))
            assertThat(decoded.attestation).isEqualTo(vector.toAttestation())
            assertThat(Hex.encode(decoded.recordHash)).isEqualTo(vector.text("recordHashHex"))
            assertThat(
                AttestationCodec.verifySignature(decoded, document.bytes("sitePublicKeyHex")),
            ).isTrue()
        }
    }

    @Test
    fun `worker id hashes match their plaintext ids`() {
        vectors.forEach { vector ->
            assertThat(Hex.encode(Sha256.hashUtf8(vector.text("workerId"))))
                .isEqualTo(vector.text("workerIdHashHex"))
        }
    }

    @Test
    fun `fixtures cover the format boundaries`() {
        // A parity suite that only exercised typical values would miss exactly the cases where two
        // implementations disagree: zero, maximum, and every flag set.
        val names = vectors.map { it.text("name") }
        assertThat(names).containsAtLeast("genesis", "min_field_values", "max_field_values")

        val max = vectors.single { it.text("name") == "max_field_values" }
        assertThat(max.number("seq")).isEqualTo(Attestation.MAX_SEQ)
        assertThat(max.number("medianLatencyMs")).isEqualTo(Attestation.MAX_U32)
        assertThat(max.number("issuedAtEpochMin")).isEqualTo(Attestation.MAX_U32)
        assertThat(max.number("outcomeFlags").toInt()).isEqualTo(OutcomeFlags.ALL_MASK)
        assertThat(max.text("siteId")).hasLength(Attestation.MAX_SITE_ID_BYTES)

        val min = vectors.single { it.text("name") == "min_field_values" }
        assertThat(min.number("medianLatencyMs")).isEqualTo(0L)
        assertThat(min.number("issuedAtEpochMin")).isEqualTo(0L)
        assertThat(min.number("outcomeFlags").toInt()).isEqualTo(0)
    }

    @Test
    fun `every fixture qr stays inside the scannable budget`() {
        vectors.forEach { vector ->
            val payload = vector.text("qrText").removePrefix(QrCodec.TEXT_PREFIX)
            assertThat(org.jaagruk.core.util.Base64Url.decode(payload).size)
                .isAtMost(QrCodec.MAX_PAYLOAD_BYTES)
        }
    }

    @Test
    fun `the committed file is exactly what the generator produces`() {
        // If this fails, either the format changed without regenerating the fixtures, or the
        // fixtures were hand-edited. Both would silently break server-side verification.
        val committed = requireNotNull(
            javaClass.getResourceAsStream("/fixtures/attestation_vectors.json"),
        ).bufferedReader().use { it.readText() }

        assertThat(FixtureGenerator.render().replace("\r\n", "\n"))
            .isEqualTo(committed.replace("\r\n", "\n"))
    }
}
