package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.cnCompare
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@Dao
interface BookSourceDao {

    data class BookSourceRuleScore(
        val bookSourceUrl: String,
        val score: Int,
    )

    data class BookSourceGroupEntry(
        val bookSourceUrl: String,
        val bookSourceGroup: String?,
    )

    data class BookSourceR18ScanEntry(
        val bookSourceUrl: String,
        val bookSourceName: String,
        val bookSourceGroup: String?,
        val bookSourceComment: String?,
        val exploreUrl: String?,
        val searchUrl: String?,
    )

    @Query(
        """select bookSourceUrl,
        (case when length(searchUrl) > 0 or length(mainJs) > 0 then 2 else 0 end) +
        (case when length(exploreUrl) > 0 then 2 else 0 end) +
        (case when length(ruleToc) > 4 then 2 else 0 end) +
        (case when length(ruleContent) > 4 then 2 else 0 end) +
        (case when length(ruleBookInfo) > 4 then 1 else 0 end) +
        (case when length(mainJs) > 0 then 4 else 0 end) as score
        from book_sources"""
    )
    fun allRuleScores(): List<BookSourceRuleScore>

    @Query("select * from book_sources_part order by customOrder asc")
    fun flowAll(): Flow<List<BookSourcePart>>

    @Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.bookSourceName like '%' || :searchKey || '%'
        or b.bookSourceGroup like '%' || :searchKey || '%'
        or b.bookSourceUrl like '%' || :searchKey || '%'
        or b.bookSourceComment like '%' || :searchKey || '%' 
        order by b.customOrder asc"""
    )
    fun flowSearch(searchKey: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources 
        where bookSourceName like '%' || :searchKey || '%'
        or bookSourceGroup like '%' || :searchKey || '%'
        or bookSourceUrl like '%' || :searchKey || '%'
        or bookSourceComment like '%' || :searchKey || '%' 
        order by customOrder asc"""
    )
    fun search(searchKey: String): List<BookSource>

    @Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.enabled = 1 
        and (b.bookSourceName like '%' || :searchKey || '%' 
        or b.bookSourceGroup like '%' || :searchKey || '%' 
        or b.bookSourceUrl like '%' || :searchKey || '%'  
        or b.bookSourceComment like '%' || :searchKey || '%')
        order by b.customOrder asc"""
    )
    fun flowSearchEnabled(searchKey: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where bookSourceGroup = :searchKey
        or bookSourceGroup like :searchKey || ',%' 
        or bookSourceGroup like  '%,' || :searchKey
        or bookSourceGroup like  '%,' || :searchKey || ',%' 
        order by customOrder asc"""
    )
    fun flowGroupSearch(searchKey: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources 
        where bookSourceGroup = :searchKey
        or bookSourceGroup like :searchKey || ',%' 
        or bookSourceGroup like  '%,' || :searchKey
        or bookSourceGroup like  '%,' || :searchKey || ',%' 
        order by customOrder asc"""
    )
    fun groupSearch(searchKey: String): List<BookSource>

    @Query("select * from book_sources_part where bookSourceType = :type order by customOrder asc")
    fun flowType(type: Int): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabled = 1 order by customOrder asc")
    fun flowEnabled(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabled = 0 order by customOrder asc")
    fun flowDisabled(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where enabledExplore = 1 and hasExploreUrl = 1 order by customOrder asc"""
    )
    fun flowExplore(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where hasLoginUrl = 1 order by customOrder asc")
    fun flowLogin(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where bookSourceGroup is null or bookSourceGroup = '' or bookSourceGroup like '%未分组%'
        order by customOrder asc"""
    )
    fun flowNoGroup(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabledExplore = 1 order by customOrder asc")
    fun flowEnabledExplore(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabledExplore = 0 order by customOrder asc")
    fun flowDisabledExplore(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where enabledExplore = 1 
        and hasExploreUrl = 1 
        and bookSourceName like '%' || :key || '%'
        order by customOrder asc"""
    )
    fun flowExploreByName(key: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where enabledExplore = 1 
        and hasExploreUrl = 1 
        and (bookSourceGroup = :key
            or bookSourceGroup like :key || ',%' 
            or bookSourceGroup like  '%,' || :key
            or bookSourceGroup like  '%,' || :key || ',%') 
        order by customOrder asc"""
    )
    fun flowGroupExplore(key: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part
        where enabledExplore = 1
        and hasExploreUrl = 1
        and bookSourceType = :type
        order by customOrder asc"""
    )
    fun flowTypeExplore(type: Int): Flow<List<BookSourcePart>>

    @Query("select distinct bookSourceGroup from book_sources where trim(bookSourceGroup) <> ''")
    fun flowGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabled = 1 and trim(bookSourceGroup) <> ''"""
    )
    fun flowEnabledGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabledExplore = 1 
        and trim(exploreUrl) <> '' 
        and trim(bookSourceGroup) <> ''
        order by customOrder"""
    )
    fun flowExploreGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select * from book_sources 
        where bookSourceGroup like '%' || :group || '%' order by customOrder asc"""
    )
    fun getByGroup(group: String): List<BookSource>

    @Query(
        """select * from book_sources
        where bookSourceGroup = :group
            or bookSourceGroup like :group || ',%'
            or bookSourceGroup like '%,' || :group
            or bookSourceGroup like '%,' || :group || ',%'
        order by customOrder asc"""
    )
    fun getExactByGroup(group: String): List<BookSource>

    @Query(
        """select * from book_sources 
        where enabled = 1 
        and (bookSourceGroup = :group
            or bookSourceGroup like :group || ',%' 
            or bookSourceGroup like  '%,' || :group
            or bookSourceGroup like  '%,' || :group || ',%')
        order by customOrder asc"""
    )
    fun getEnabledByGroup(group: String): List<BookSource>

    @Query(
        """select * from book_sources_part 
        where enabled = 1 
        and (bookSourceGroup = :group
            or bookSourceGroup like :group || ',%' 
            or bookSourceGroup like  '%,' || :group
            or bookSourceGroup like  '%,' || :group || ',%')
        order by customOrder asc"""
    )
    fun getEnabledPartByGroup(group: String): List<BookSourcePart>

    @Query(
        """select * from book_sources 
        where bookUrlPattern != 'NONE' and bookSourceType = :type order by customOrder asc"""
    )
    fun getEnabledByType(type: Int): List<BookSource>

    @Query("select * from book_sources where enabled = 1 and bookSourceUrl = :baseUrl")
    fun getBookSourceAddBook(baseUrl: String): BookSource?

    @get:Query(
        """select bp.* 
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl
        where b.enabled = 1 
        and trim(b.bookUrlPattern) <> '' 
        and trim(b.bookUrlPattern) <> 'NONE' 
        order by b.customOrder"""
    )
    val hasBookUrlPattern: List<BookSourcePart>

    @get:Query("select * from book_sources where bookSourceGroup is null or bookSourceGroup = ''")
    val noGroup: List<BookSource>

    @get:Query("select * from book_sources order by customOrder asc")
    val all: List<BookSource>

    @get:Query("select * from book_sources_part order by customOrder asc")
    val allPart: List<BookSourcePart>

    @get:Query("select * from book_sources where enabled = 1 order by customOrder")
    val allEnabled: List<BookSource>

    @get:Query("select * from book_sources_part where enabled = 1 order by customOrder asc")
    val allEnabledPart: List<BookSourcePart>

    @get:Query("select * from book_sources where enabled = 0 order by customOrder")
    val allDisabled: List<BookSource>

    @get:Query(
        """select * from book_sources 
        where bookSourceGroup is null or bookSourceGroup = '' or bookSourceGroup like '%未分组%'"""
    )
    val allNoGroup: List<BookSource>

    @get:Query("select * from book_sources where enabledExplore = 1 order by customOrder")
    val allEnabledExplore: List<BookSource>

    @get:Query("select * from book_sources where enabledExplore = 0 order by customOrder")
    val allDisabledExplore: List<BookSource>

    @get:Query(
        """select * from book_sources
        where (loginUrl is not null and trim(loginUrl) <> '')
        or (mainJs is not null and trim(mainJs) <> ''
            and loginUi is not null
            and replace(replace(replace(replace(loginUi, ' ', ''), char(9), ''), char(10), ''), char(13), '') not in ('', '[]'))"""
    )
    val allLogin: List<BookSource>

    @get:Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.enabled = 1 and b.bookSourceType = 0 order by b.customOrder"""
    )
    val allTextEnabledPart: List<BookSourcePart>

    @get:Query(
        """select distinct bookSourceGroup from book_sources 
        where trim(bookSourceGroup) <> ''"""
    )
    val allGroupsUnProcessed: List<String>

    @get:Query(
        """select distinct bookSourceGroup from book_sources 
        where enabled = 1 and trim(bookSourceGroup) <> ''"""
    )
    val allEnabledGroupsUnProcessed: List<String>

    @Query("select * from book_sources where bookSourceUrl = :key")
    fun getBookSource(key: String): BookSource?

    @Query("select * from book_sources where bookSourceUrl in (:keys)")
    fun getBookSources(keys: List<String>): List<BookSource>

    @Query("select * from book_sources_part where bookSourceUrl = :key")
    fun getBookSourcePart(key: String): BookSourcePart?

    @Query("select count(*) from book_sources")
    fun allCount(): Int

    @Query("SELECT EXISTS(select 1 from book_sources where bookSourceUrl = :key)")
    fun has(key: String): Boolean

    @Query("select mainJs from book_sources where bookSourceUrl = :key")
    fun getMainJs(key: String): String?

    fun hasJsSource(key: String): Boolean = !getMainJs(key).isNullOrBlank()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookSource: BookSource)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(bookSources: List<BookSource>)

    @Update
    fun update(vararg bookSource: BookSource)

    @Delete
    fun delete(vararg bookSource: BookSource)

    @Query("delete from book_sources where bookSourceUrl = :key")
    fun delete(key: String)

    @Query("delete from book_sources where bookSourceUrl in (:keys)")
    fun deleteByKeys(keys: List<String>)

    @Query("select bookSourceUrl from book_sources")
    fun allKeys(): List<String>

    @Query("delete from book_sources")
    fun deleteAll()

    fun deleteByKeysChunked(keys: Collection<String>) {
        keys.chunked(900).forEach(::deleteByKeys)
    }

    @Transaction
    fun delete(bookSources: List<BookSourcePart>) {
        deleteByKeysChunked(bookSources.map { it.bookSourceUrl })
    }

    @get:Query("select min(customOrder) from book_sources")
    val minOrder: Int

    @get:Query("select max(customOrder) from book_sources")
    val maxOrder: Int

    @get:Query(
        """select exists (select 1 
        from book_sources group by customOrder having count(customOrder) > 1)"""
    )
    val hasDuplicateOrder: Boolean

    @Query(
        """select bookSourceUrl, bookSourceGroup from book_sources
        where bookSourceGroup like '%' || :group || '%'"""
    )
    fun getGroupEntriesByGroup(group: String): List<BookSourceGroupEntry>

    @Query(
        """select bookSourceUrl, bookSourceName, bookSourceGroup, bookSourceComment, exploreUrl, searchUrl
        from book_sources
        where bookSourceUrl in (:keys)"""
    )
    fun getR18ScanEntriesByKeys(keys: List<String>): List<BookSourceR18ScanEntry>

    @Query(
        """select bookSourceUrl, bookSourceGroup from book_sources
        where bookSourceGroup = :group
            or bookSourceGroup like :group || ',%'
            or bookSourceGroup like '%,' || :group
            or bookSourceGroup like '%,' || :group || ',%'"""
    )
    fun getExactGroupEntriesByGroup(group: String): List<BookSourceGroupEntry>

    @Query(
        """update book_sources
        set bookSourceGroup = :group
        where bookSourceGroup is null or trim(bookSourceGroup) = '' or bookSourceGroup like '%未分组%'"""
    )
    fun assignGroupToNoGroup(group: String)

    @Query("update book_sources set enabled = :enable where bookSourceUrl = :bookSourceUrl")
    fun enable(bookSourceUrl: String, enable: Boolean)

    @Query("update book_sources set enabled = :enable where bookSourceUrl in (:keys)")
    fun enableByKeys(enable: Boolean, keys: List<String>)

    @Transaction
    fun enable(enable: Boolean, bookSources: List<BookSourcePart>) {
        val urls = bookSources
            .asSequence()
            .filter { it.enabled != enable }
            .map { it.bookSourceUrl }
            .distinct()
            .toList()
        if (urls.isEmpty()) return
        urls.chunked(900).forEach { chunk ->
            enableByKeys(enable, chunk)
        }
    }

    @Query("update book_sources set enabledExplore = :enable where bookSourceUrl = :bookSourceUrl")
    fun enableExplore(bookSourceUrl: String, enable: Boolean)

    @Query("update book_sources set enabledExplore = :enable where bookSourceUrl in (:keys)")
    fun enableExploreByKeys(enable: Boolean, keys: List<String>)

    @Transaction
    fun enableExplore(enable: Boolean, bookSources: List<BookSourcePart>) {
        val urls = bookSources
            .asSequence()
            .filter { it.enabledExplore != enable }
            .map { it.bookSourceUrl }
            .distinct()
            .toList()
        if (urls.isEmpty()) return
        urls.chunked(900).forEach { chunk ->
            enableExploreByKeys(enable, chunk)
        }
    }

    @Query("update book_sources set bookSourceType = :bookSourceType where bookSourceUrl in (:keys)")
    fun upTypeByKeys(bookSourceType: Int, keys: List<String>)

    @Transaction
    fun upType(bookSourceType: Int, bookSources: List<BookSourcePart>) {
        val urlsToUpdate = bookSources
            .asSequence()
            .filter { it.bookSourceType != bookSourceType }
            .map { it.bookSourceUrl }
            .distinct()
            .toList()
        if (urlsToUpdate.isEmpty()) return
        urlsToUpdate.chunked(900).forEach { chunk ->
            upTypeByKeys(bookSourceType, chunk)
        }
    }

    @Query(
        """update book_sources 
        set customOrder = :customOrder where bookSourceUrl = :bookSourceUrl"""
    )
    fun upOrder(bookSourceUrl: String, customOrder: Int)

    @Transaction
    fun upOrder(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            upOrder(bs.bookSourceUrl, bs.customOrder)
        }
    }

    fun upOrder(bookSource: BookSourcePart) {
        upOrder(bookSource.bookSourceUrl, bookSource.customOrder)
    }

    @Query(
        """update book_sources 
        set bookSourceGroup = :bookSourceGroup where bookSourceUrl = :bookSourceUrl"""
    )
    fun upGroup(bookSourceUrl: String, bookSourceGroup: String)

    @Query(
        """update book_sources
        set bookSourceGroup = :bookSourceGroup where bookSourceUrl in (:keys)"""
    )
    fun upGroupByKeys(bookSourceGroup: String, keys: List<String>)

    @Transaction
    fun upGroupBatch(updates: Map<String, List<String>>) {
        updates.forEach { (newGroup, urls) ->
            if (urls.isNotEmpty()) {
                urls.chunked(900).forEach { chunk ->
                    upGroupByKeys(newGroup, chunk)
                }
            }
        }
    }

    @Transaction
    fun upGroup(bookSources: List<BookSourcePart>) {
        if (bookSources.isEmpty()) return
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (bs in bookSources) {
            val newGroup = bs.bookSourceGroup ?: continue
            grouped.getOrPut(newGroup) { arrayListOf() }.add(bs.bookSourceUrl)
        }
        upGroupBatch(grouped)
    }

    private fun dealGroups(list: List<String>): List<String> {
        val groups = linkedSetOf<String>()
        list.forEach {
            it.splitNotBlank(AppPattern.splitGroupRegex).forEach { group ->
                groups.add(group)
            }
        }
        return groups.sortedWith { o1, o2 ->
            o1.cnCompare(o2)
        }
    }

    fun allGroups(): List<String> = dealGroups(allGroupsUnProcessed)

    fun allEnabledGroups(): List<String> = dealGroups(allEnabledGroupsUnProcessed)

    fun flowGroups(): Flow<List<String>> {
        return flowGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }

    fun flowExploreGroups(): Flow<List<String>> {
        return flowExploreGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }

    fun flowEnabledGroups(): Flow<List<String>> {
        return flowEnabledGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }
}
