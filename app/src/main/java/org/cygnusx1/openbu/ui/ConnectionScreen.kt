package org.cygnusx1.openbu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cygnusx1.openbu.viewmodel.ConnectionState

@Composable
fun ConnectionScreen(
    savedIp: String,
    savedAccessCode: String,
    savedSerialNumber: String,
    connectionState: ConnectionState,
    errorMessage: String?,
    onConnect: (ip: String, accessCode: String, serialNumber: String) -> Unit,
) {
    var ip by rememberSaveable { mutableStateOf(savedIp) }
    var accessCode by rememberSaveable { mutableStateOf(savedAccessCode) }
    var serialNumber by rememberSaveable { mutableStateOf(savedSerialNumber) }
    val isConnecting = connectionState == ConnectionState.Connecting

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
    )

    val serialValid = serialNumber.length == 15
    val allFieldsFilled = ip.isNotBlank() && accessCode.isNotBlank() && serialValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Openbu",
            style = TextStyle(fontSize = 32.sp, color = Color.Black),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Connect to your Bambu Lab P1S camera",
            style = TextStyle(fontSize = 14.sp, color = Color.DarkGray),
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it.trim() },
            label = { Text("Printer IP Address") },
            placeholder = { Text("192.168.1.100") },
            singleLine = true,
            colors = textFieldColors,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = accessCode,
            onValueChange = { accessCode = it.trim() },
            label = { Text("Access Code") },
            singleLine = true,
            colors = textFieldColors,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = serialNumber,
            onValueChange = { serialNumber = it.trim() },
            label = { Text("Printer Serial Number") },
            supportingText = {
                if (serialNumber.isNotBlank() && !serialValid) {
                    Text("Must be exactly 15 characters (currently ${serialNumber.length})", color = Color.Red)
                }
            },
            singleLine = true,
            colors = textFieldColors,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (allFieldsFilled) {
                        onConnect(ip, accessCode, serialNumber)
                    }
                },
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isConnecting) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { onConnect(ip, accessCode, serialNumber) },
                enabled = allFieldsFilled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorMessage,
                color = Color.Red,
                style = TextStyle(fontSize = 14.sp),
            )
        }
    }
}
