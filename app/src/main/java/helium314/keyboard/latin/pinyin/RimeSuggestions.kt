package helium314.keyboard.latin.pinyin

import helium314.keyboard.latin.InputStyle
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords

fun rimeSuggestedWords(message: String): SuggestedWords {
    val info = rimeInfo(message, "", -1)
    return wordsOf(listOf(info), pageNo = 0, lastPage = true)
}

fun rimeSuggestedWords(snapshot: RimeSnapshot): SuggestedWords {
    val infos = snapshot.candidates.mapIndexed { index, candidate ->
        val info = rimeInfo(candidate.text, candidate.comment, index)
        info.mRimePage = snapshot.pageNo
        info
    }
    if (infos.isEmpty()) return SuggestedWords.emptyInstance
    return wordsOf(infos, snapshot.pageNo, snapshot.isLastPage)
}

/** The whole grid, in one strip. No page buttons: the row itself scrolls. */
fun rimeSuggestedWords(menu: List<MenuChar>): SuggestedWords {
    if (menu.isEmpty()) return SuggestedWords.emptyInstance
    val infos = menu.map { item ->
        val info = rimeInfo(item.text, item.comment, item.index)
        info.mRimePage = item.page
        info
    }
    return wordsOf(infos, pageNo = 0, lastPage = true)
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
        InputStyle.RIME, SuggestedWords.NOT_A_SEQUENCE_NUMBER,
    )
    words.mRimePageNo = pageNo
    words.mRimeLastPage = lastPage
    return words
}
