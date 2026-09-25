package com.shortyurl.service;

import com.shortyurl.persistence.Binding;

public record CreateUrlResult(Binding binding, boolean created) {
}
