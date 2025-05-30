import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phelela_mind.data.sudoku.SudokuDao
import com.example.phelela_mind.data.sudoku.SudokuEntity
import com.example.phelela_mind.domain.sudoku.GenerateSudokuUseCase
import com.example.phelela_mind.domain.sudoku.MaskSudokuUseCase
import com.example.phelela_mind.domain.sudoku.SudokuDifficulty
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SudokuViewModel(
    private val generateSudokuUseCase: GenerateSudokuUseCase,
    private val maskSudokuUseCase: MaskSudokuUseCase,
    private val sudokuDao: SudokuDao
) : ViewModel() {

    private val gson = Gson()

    private val _completeGrid = MutableStateFlow<Array<IntArray>>(emptyArray())
    val completeGrid: StateFlow<Array<IntArray>> = _completeGrid

    private val _visibleMask = MutableStateFlow<Array<Array<Boolean>>>(emptyArray())

    private val _sudokuState = MutableStateFlow<List<List<Int?>>>(emptyList())
    val sudokuState: StateFlow<List<List<Int?>>> = _sudokuState

    private val _originalCells = MutableStateFlow<List<List<Boolean>>>(emptyList())
    val originalCells: StateFlow<List<List<Boolean>>> = _originalCells

    private val _selectedCell = MutableStateFlow<Pair<Int, Int>?>(null)
    val selectedCell: StateFlow<Pair<Int, Int>?> = _selectedCell

    private val _difficulty = MutableStateFlow(SudokuDifficulty.MEDIUM)
    val difficulty: StateFlow<SudokuDifficulty> = _difficulty

    private var elapsedTime: Long = 0L
    private val _time = MutableStateFlow(0L)
    val time: StateFlow<Long> = _time
    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            val existingGame = sudokuDao.getUnfinishedSudoku()
            if (existingGame != null) {
                loadFromEntity(existingGame)
            } else {
                generateNewGame(_difficulty.value)
            }
        }
    }

    fun generateNewGame(level: SudokuDifficulty) {
        viewModelScope.launch {
            _difficulty.value = level

            val fullGrid = generateSudokuUseCase.generateSudokuGrid()
            val mask = maskSudokuUseCase.generateVisibleMask(level)

            _completeGrid.value = fullGrid
            _visibleMask.value = mask

            _sudokuState.value = fullGrid.mapIndexed { row, rowList ->
                rowList.mapIndexed { col, value ->
                    if (mask[row][col]) value else null
                }
            }

            _originalCells.value = mask.map { it.toList() }
            _selectedCell.value = null

            elapsedTime = 0L
            _time.value = 0L
            startTimer()

            saveCurrentGame()
        }
    }

    fun selectCell(row: Int, col: Int) {
        _selectedCell.value = if (_selectedCell.value == Pair(row, col)) null else Pair(row, col)
    }

    fun setNumber(row: Int, col: Int, number: Int) {
        if (_originalCells.value[row][col]) return
        updateCell(row, col, number)
    }

    fun eraseNumber(row: Int, col: Int) {
        if (_originalCells.value[row][col]) return
        updateCell(row, col, null)
    }

    private fun updateCell(row: Int, col: Int, number: Int?) {
        val currentState = _sudokuState.value.toMutableList()
        val rowList = currentState[row].toMutableList()
        rowList[col] = number
        currentState[row] = rowList
        _sudokuState.value = currentState
        saveCurrentGame()
    }

    fun getHint(row: Int, col: Int) {
        if (_originalCells.value[row][col]) return
        val answer = _completeGrid.value[row][col]
        setNumber(row, col, answer)
    }

    fun restartGame() {
        _sudokuState.value = _completeGrid.value.mapIndexed { row, rowList ->
            rowList.mapIndexed { col, value ->
                if (_visibleMask.value[row][col]) value else null
            }
        }
        _selectedCell.value = null

        startTimer()
        saveCurrentGame()
    }

    fun changeDifficulty(level: SudokuDifficulty) {
        generateNewGame(level)
    }

    private fun saveCurrentGame() {
        viewModelScope.launch {
            val entity = SudokuEntity(
                initialGrid = gson.toJson(_completeGrid.value),
                maskedGrid = gson.toJson(_visibleMask.value),
                userGrid = gson.toJson(_sudokuState.value),
                difficulty = _difficulty.value.name,
                timeSpent = elapsedTime,
                isCompleted = false,
                completedAt = null,
                createdAt = System.currentTimeMillis()
            )
            sudokuDao.insertSudoku(entity)
        }
    }

    private fun loadFromEntity(entity: SudokuEntity) {
        _completeGrid.value = gson.fromJson(entity.initialGrid, Array<IntArray>::class.java)
        _visibleMask.value = gson.fromJson(entity.maskedGrid, Array<Array<Boolean>>::class.java)

        val type = object : TypeToken<List<List<Int?>>>() {}.type
        _sudokuState.value = gson.fromJson(entity.userGrid, type)

        _originalCells.value = _visibleMask.value.map { it.toList() }
        _difficulty.value = SudokuDifficulty.valueOf(entity.difficulty)
        _selectedCell.value = null

        elapsedTime = entity.timeSpent
        _time.value = elapsedTime

        startTimer()
    }

    fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                elapsedTime++
                _time.value = elapsedTime
                saveCurrentGame()
            }
        }
    }

    fun stopTimer() {
        timerJob?.cancel()
        saveCurrentGame()
    }

    override fun onCleared() {
        super.onCleared()
        stopTimer()
    }
}
