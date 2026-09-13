package com.inversionadvisor.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Cabecera de sección reutilizable, con una línea divisoria sutil debajo. */
@Composable
fun SectionHeader(title: String) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp),
            color = Color(0xFFE0E0E0)
        ) {}
    }
}
