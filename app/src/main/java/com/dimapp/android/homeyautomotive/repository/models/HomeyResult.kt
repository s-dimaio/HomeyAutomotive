package com.dimapp.android.homeyautomotive.repository.models
 
 /**
  * Result wrapper for repository operations.
  *
  * @param T Type of the success payload.
  */
 sealed class HomeyResult<out T> {
     /** Operation succeeded; [data] contains the result. */
     data class Success<T>(val data: T) : HomeyResult<T>()
 
     /** Operation failed; [message] contains a human-readable error. */
     data class Error(val message: String) : HomeyResult<Nothing>()
 }
