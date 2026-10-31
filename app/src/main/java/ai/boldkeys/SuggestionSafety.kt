package ai.boldkeys

import java.text.Normalizer
import java.util.Locale

/** Local, authored output policy, not a classification of what the user may type.
 * Whole lexical forms only: no substring, fuzzy matching, or accent stripping.
 * All three lists apply across layouts so changing language cannot surface a blocked alternative.
 * Ambiguous names/homographs and ordinary clinical/health vocabulary are deliberately omitted.
 * Finite lists cannot recognize every spelling, compound, euphemism or contextual meaning.
 */
object SuggestionSafety {
    private fun forms(text: String) = text.trim().split(Regex("\\s+")).toSet()
    private val english = forms("""
        fuck fucks fucked fucking fuckin fuckin' fuckhead fuckheads fuckface fuckup fuckups fuckwit fuckwits fucker fuckers motherfucker motherfuckers motherfucking motherfuckin motherfuckin' motherfuck
        shit shits shitty shitting shittin shittin' shite shithead shitheads shithole shitholes
        shitload shitless shitter shitfaced shithouse shitbag shitbags shitstorm
        bullshit bullshitting bullshitter bullshitters asshole assholes arsehole arseholes
        cunt cunts twat twats cock cocks pussy pussies dickhead dickheads
        slut sluts whore whores tits titties boobs boner boners cocksucker cocksuckers cocksucking bitch bitches bastard bastards
        nigger niggers nigga niggas faggot faggots spic spics kike kikes chink chinks
        porn porno pornography pornographic pornos pornstar pornstars xxx blowjob blowjobs handjob handjobs
        hentai bdsm sextape sextapes cum cums cumshot cumshots gangbang gangbangs gangbanging bukkake deepthroat
        fuck's motherfucker's fucker's asshole's cunt's
    """)
    private val french = forms("""
        merde merdes merdique merdiques merdé merdée merdés merdées merder merdier merdiers merdeux putain putains putasse putasses
        pute putes couille couilles connard connards connasse connasses salope salopes salaud salauds
        enculé enculés enculée enculées enculer encule encules enculent
        enfoiré enfoirés enfoirée enfoirées foutre foutu foutus foutue foutues
        pédé pédés pede pedes tapette tapettes négro négros negre negres nègre nègres
        bougnoule bougnoules youpin youpins
        porno pornos pornographie pornographique pornographiques fellation fellations
        branlette branlettes branler niquer niqué niqués niquée niquées cumshot
    """)
    private val german = forms("""
        scheiße scheisse scheiß scheiss scheißt scheisst scheißen scheissen
        scheißkerl scheisskerl scheißkerle scheisskerle scheißegal scheissegal scheißer scheisser
        scheißdreck scheissdreck scheißding scheissding scheißhaufen scheisshaufen
        scheißangst scheissangst scheißhaus scheisshaus scheißtag scheisstag scheißjob scheissjob
        scheißleben scheissleben scheißloch scheissloch scheißkarre scheisskarre
        scheißteil scheissteil scheißtyp scheisstyp scheißladen scheissladen scheißtür scheisstür
        wichsern wichse wichst pornostar pornostars
        verfickt hure huren schwanz schwänze schwaenze beschissen arschloch arschlöcher arschloecher wichser wichsers wichsen
        ficken fickt fickte fickten gefickt fick hurensohn hurensöhne hurensoehne
        fotze fotzen schwuchtel schwuchteln kanake kanaken neger negers
        porno pornos pornografie pornographie pornografisch pornographisch
        blowjob blowjobs handjob handjobs gangbang bukkake
    """)
    private val blocked = english + french + german

    internal fun key(word: String): String = Normalizer.normalize(
        word.replace('’', '\'').replace('‘', '\''), Normalizer.Form.NFC
    ).lowercase(Locale.ROOT)

    fun allows(word: String): Boolean = key(word) !in blocked
}
