package dev.kiln.example.server.demos.fabrik

data class MachineTypeDto(val id: String, val label: String, val icon: String, val recipes: List<RecipeDto>)
