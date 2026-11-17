package com.ifpr.androidapptemplatear.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedModelViewModel : ViewModel() {
    val selectedModel = MutableLiveData<String>()

    private val _pontuacao = MutableLiveData(0)
    val pontuacao: LiveData<Int> = _pontuacao

    private val _modelosEncontrados = MutableLiveData<Set<String>>(emptySet())
    val modelosEncontrados: LiveData<Set<String>> = _modelosEncontrados

    private val _jogoEncerrado = MutableLiveData(false)
    val jogoEncerrado: LiveData<Boolean> = _jogoEncerrado

    fun selectModel(modelPath: String) {
        selectedModel.value = modelPath
    }

    /**
     * Cada arquivo .glb concede 10 pontos somente na primeira descoberta
     * da partida. Retorna true quando a descoberta é inédita.
     */
    fun registrarEncontro(arquivo: String): Boolean {
        if (_jogoEncerrado.value == true) return false

        val encontrados = _modelosEncontrados.value.orEmpty()
        if (!encontrados.add(arquivo)) return false

        _modelosEncontrados.value = encontrados + arquivo
        _pontuacao.value = (_pontuacao.value ?: 0) + 10
        return true
    }

    fun encerrarJogo() {
        _jogoEncerrado.value = true
    }

    fun reiniciarPartida() {
        _modelosEncontrados.value = emptySet()
        _pontuacao.value = 0
        _jogoEncerrado.value = false
        selectedModel.value = "neutrofilo.glb"
    }
}
