package helium314.keyboard.latin.pinyin

import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import java.util.ArrayList

fun rimeSuggestedWords(message: String): SuggestedWords {
    val info = rimeInfo(message, "", -1)
    return wordsOf(listOf(info), pageNo = 0, lastPage = true)
}

fun rimeSuggestedWords(snapshot: RimeSnapshot): SuggestedWords {
    val infos = snapshot.candidates.mapIndexed { index, candidate ->
        rimeInfo(candidate.text, candidate.comment, index)
    }
    if (infos.isEmpty()) return SuggestedWords.getEmptyInstance()
    return wordsOf(infos, snapshot.pageNo, snapshot.isLastPage)
}

private fun rimeInfo(text: String, comment: String, index: Int): SuggestedWordInfo {
    val info = SuggestedWordInfo(
        text, "", 0, SuggestedWordInfo.KIND_RIME,
        Dictionary.DICTIONARY_USER_TYPED,
        SuggestedWordInfo.NOT_AN_INDEX,
        SuggestedWordInfo.NOT_A_CONFIDENCE,
    )
    info.mComment = comment
    info.mCandidateIndex = index
    return info
}

private fun wordsOf(infos: List<SuggestedWordInfo>, pageNo: Int, lastPage: Boolean): SuggestedWords {
    val words = SuggestedWords(
        ArrayList(infos), null, null, false, false, false,
        SuggestedWords.INPUT_STYLE_RIME, SuggestedWords.NOT_A_SEQUENCE_NUMBER,
    )
    words.mRimePageNo = pageNo
    words.mRimeLastPage = lastPage
    return words
}
