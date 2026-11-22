package io.legado.app.help.translate

import com.google.gson.JsonParser
import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import java.io.File
import kotlin.math.min

internal data class LocalNmtConfig(
    val padTokenId: Long,
    val eosTokenId: Long,
    val bosTokenId: Long?,
    val decoderStartTokenId: Long,
    val unkTokenId: Long,
    val maxLength: Int,
)

internal interface LocalNmtTokenizer {
    fun encode(text: String, srcLang: String): LongArray
    fun initialDecoderIds(tgtLang: String): LongArray
    fun decode(ids: List<Long>): String
}

internal fun parseLocalNmtConfig(configFile: File): LocalNmtConfig {
    val json = JsonParser.parseString(configFile.readText()).asJsonObject
    val padId = json.get("pad_token_id")?.takeIf { !it.isJsonNull }?.asLong ?: 1L
    val eosId = json.get("eos_token_id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
    val bosId = json.get("bos_token_id")?.takeIf { !it.isJsonNull }?.asLong
    val startId = json.get("decoder_start_token_id")?.takeIf { !it.isJsonNull }?.asLong ?: padId
    val unkId = json.get("unk_token_id")?.takeIf { !it.isJsonNull }?.asLong ?: 3L
    val maxLen = json.get("max_length")?.takeIf { !it.isJsonNull }?.asInt ?: 512
    return LocalNmtConfig(
        padTokenId = padId,
        eosTokenId = eosId,
        bosTokenId = bosId,
        decoderStartTokenId = startId,
        unkTokenId = unkId,
        maxLength = maxLen,
    )
}

internal fun buildLocalNmtTokenizer(
    spec: LocalNmtModelSpec,
    modelDir: File,
    config: LocalNmtConfig,
): LocalNmtTokenizer {
    return when (spec.architecture) {
        LocalNmtArchitecture.MARIAN_SPM -> MarianSpmTokenizer(
            spmFile = File(modelDir, "source.spm"),
            vocabFile = File(modelDir, "vocab.json"),
            config = config,
        )
        LocalNmtArchitecture.NLLB_TOKENIZER_JSON -> NllbJsonTokenizer(
            tokenizerFile = File(modelDir, "tokenizer.json"),
            config = config,
        )
    }
}

private class UnigramSegmenter(
    private val scores: Map<String, Float>,
    private val unkId: Long,
    private val pieceToId: Map<String, Long>,
) {
    private val maxPieceLen = (scores.keys.maxOfOrNull { it.length } ?: 16).coerceIn(1, 32)

    fun segmentToIds(text: String): List<Long> {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return emptyList()
        val normalized = "\u2581" + cleaned.replace(' ', '\u2581')
        val n = normalized.length
        val dpScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val dpPrev = IntArray(n + 1) { -1 }
        val dpPiece = arrayOfNulls<String>(n + 1)
        dpScore[0] = 0f

        for (i in 0 until n) {
            if (dpScore[i] == Float.NEGATIVE_INFINITY) continue
            val maxEnd = min(n, i + maxPieceLen)
            var matchedAny = false
            for (end in (i + 1)..maxEnd) {
                val sub = normalized.substring(i, end)
                val pieceScore = scores[sub] ?: continue
                matchedAny = true
                val cand = dpScore[i] + pieceScore
                if (cand > dpScore[end]) {
                    dpScore[end] = cand
                    dpPrev[end] = i
                    dpPiece[end] = sub
                }
            }
            if (!matchedAny) {
                val stepEnd = min(n, i + Character.charCount(normalized.codePointAt(i)))
                val cand = dpScore[i] - 25f
                if (cand > dpScore[stepEnd]) {
                    dpScore[stepEnd] = cand
                    dpPrev[stepEnd] = i
                    dpPiece[stepEnd] = normalized.substring(i, stepEnd)
                }
            }
        }

        val pieces = ArrayList<String>()
        var cursor = n
        while (cursor > 0) {
            val prev = dpPrev[cursor]
            val piece = dpPiece[cursor]
            if (prev < 0 || piece == null) break
            pieces.add(piece)
            cursor = prev
        }
        pieces.reverse()

        val out = ArrayList<Long>(pieces.size)
        for (piece in pieces) {
            val direct = pieceToId[piece]
            if (direct != null) {
                out.add(direct)
            } else {
                var usedBytes = false
                for (b in piece.toByteArray(Charsets.UTF_8)) {
                    val hexTag = "<0x%02X>".format(b.toInt() and 0xFF)
                    val byteId = pieceToId[hexTag]
                    if (byteId != null) {
                        out.add(byteId)
                        usedBytes = true
                    }
                }
                if (!usedBytes) {
                    out.add(unkId)
                }
            }
        }
        return out
    }
}

private class MarianSpmTokenizer(
    spmFile: File,
    vocabFile: File,
    private val config: LocalNmtConfig,
) : LocalNmtTokenizer {

    private val pieceToId = HashMap<String, Long>(65536)
    private val idToPiece = HashMap<Long, String>(65536)
    private val segmenter: UnigramSegmenter

    init {
        val vocabJson = JsonParser.parseString(vocabFile.readText()).asJsonObject
        for ((token, el) in vocabJson.entrySet()) {
            val id = el.asLong
            pieceToId[token] = id
            idToPiece[id] = token
        }
        val spmScores = parseSentencePieceModel(spmFile)
        val mergedScores = HashMap<String, Float>(pieceToId.size)
        for ((piece, score) in spmScores) {
            if (piece in pieceToId) {
                mergedScores[piece] = score
            }
        }
        for (piece in pieceToId.keys) {
            mergedScores.putIfAbsent(piece, -10f - piece.length * 0.5f)
        }
        segmenter = UnigramSegmenter(mergedScores, config.unkTokenId, pieceToId)
    }

    override fun encode(text: String, srcLang: String): LongArray {
        val ids = segmenter.segmentToIds(text)
        val maxBody = (config.maxLength - 1).coerceAtLeast(8)
        val body = if (ids.size > maxBody) ids.subList(0, maxBody) else ids
        return LongArray(body.size + 1) { idx ->
            if (idx < body.size) body[idx] else config.eosTokenId
        }
    }

    override fun initialDecoderIds(tgtLang: String): LongArray =
        longArrayOf(config.decoderStartTokenId)

    override fun decode(ids: List<Long>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == config.padTokenId || id == config.eosTokenId || id == config.decoderStartTokenId) continue
            val token = idToPiece[id] ?: continue
            if (token == "<unk>" || token == "</s>" || token == "<pad>") continue
            sb.append(token)
        }
        return sb.toString()
            .replace('\u2581', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

private class NllbJsonTokenizer(
    tokenizerFile: File,
    private val config: LocalNmtConfig,
) : LocalNmtTokenizer {

    private val pieceToId = HashMap<String, Long>(262144)
    private val idToPiece = HashMap<Long, String>(262144)
    private val specialTokens = HashMap<String, Long>()
    private val specialIds = HashSet<Long>()
    private val segmenter: UnigramSegmenter

    init {
        val root = JsonParser.parseString(tokenizerFile.readText()).asJsonObject
        val addedTokens = root.getAsJsonArray("added_tokens")
        addedTokens?.forEach { el ->
            val obj = el.asJsonObject
            val id = obj.get("id")?.asLong ?: return@forEach
            val content = obj.get("content")?.asString ?: return@forEach
            val isSpecial = obj.get("special")?.asBoolean ?: false
            pieceToId[content] = id
            idToPiece[id] = content
            if (isSpecial) {
                specialTokens[content] = id
                specialIds.add(id)
            }
        }
        val modelObj = root.getAsJsonObject("model")
        val unkId = modelObj?.get("unk_id")?.takeIf { !it.isJsonNull }?.asLong ?: config.unkTokenId
        val scores = HashMap<String, Float>(262144)
        val vocabEl = modelObj?.get("vocab")
        when {
            vocabEl != null && vocabEl.isJsonArray -> {
                val arr = vocabEl.asJsonArray
                for (index in 0 until arr.size()) {
                    val pair = arr[index].asJsonArray
                    val piece = pair[0].asString
                    val score = if (pair.size() > 1 && !pair[1].isJsonNull) pair[1].asFloat else -5f
                    val id = index.toLong()
                    pieceToId.putIfAbsent(piece, id)
                    idToPiece.putIfAbsent(id, piece)
                    if (!piece.startsWith("<") || !piece.endsWith(">")) {
                        scores[piece] = score
                    }
                }
            }
            vocabEl != null && vocabEl.isJsonObject -> {
                for ((piece, el) in vocabEl.asJsonObject.entrySet()) {
                    val id = el.asLong
                    pieceToId.putIfAbsent(piece, id)
                    idToPiece.putIfAbsent(id, piece)
                    scores[piece] = -piece.length.toFloat()
                }
            }
        }
        segmenter = UnigramSegmenter(scores, unkId, pieceToId)
    }

    override fun encode(text: String, srcLang: String): LongArray {
        val srcNllb = LocalLanguageDetector.toNllbCode(srcLang)
        val srcTagId = specialTokens[srcNllb] ?: pieceToId[srcNllb]
        val ids = segmenter.segmentToIds(text)
        val maxBody = (config.maxLength - 2).coerceAtLeast(8)
        val body = if (ids.size > maxBody) ids.subList(0, maxBody) else ids
        return buildList(body.size + 2) {
            if (srcTagId != null) add(srcTagId)
            addAll(body)
            add(config.eosTokenId)
        }.toLongArray()
    }

    override fun initialDecoderIds(tgtLang: String): LongArray {
        val tgtNllb = LocalLanguageDetector.toNllbCode(tgtLang)
        val tgtTagId = specialTokens[tgtNllb] ?: pieceToId[tgtNllb]
        return if (tgtTagId != null) {
            longArrayOf(config.eosTokenId, tgtTagId)
        } else {
            longArrayOf(config.decoderStartTokenId)
        }
    }

    override fun decode(ids: List<Long>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == config.padTokenId || id == config.eosTokenId || id in specialIds) continue
            val token = idToPiece[id] ?: continue
            if (token.startsWith("<<") || token == "<unk>" || token == "</s>" || token == "<pad>") continue
            sb.append(token)
        }
        return sb.toString()
            .replace('\u2581', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

private fun parseSentencePieceModel(spmFile: File): Map<String, Float> {
    val scores = HashMap<String, Float>(65536)
    val bytes = spmFile.readBytes()
    val input = CodedInputStream.newInstance(bytes)
    while (!input.isAtEnd) {
        val tag = input.readTag()
        if (tag == 0) break
        val fieldNumber = WireFormat.getTagFieldNumber(tag)
        val wireType = WireFormat.getTagWireType(tag)
        if (fieldNumber == 1 && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            val length = input.readRawVarint32()
            val oldLimit = input.pushLimit(length)
            var piece = ""
            var score = -10f
            var type = 1
            while (!input.isAtEnd) {
                val subTag = input.readTag()
                if (subTag == 0) break
                when (WireFormat.getTagFieldNumber(subTag)) {
                    1 -> piece = input.readString()
                    2 -> score = input.readFloat()
                    3 -> type = input.readEnum()
                    else -> input.skipField(subTag)
                }
            }
            input.popLimit(oldLimit)
            if (piece.isNotEmpty() && type == 1) {
                scores[piece] = score
            }
        } else {
            input.skipField(tag)
        }
    }
    return scores
}
