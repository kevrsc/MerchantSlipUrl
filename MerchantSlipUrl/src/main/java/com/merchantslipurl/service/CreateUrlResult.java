package com.merchantslipurl.service;

import com.merchantslipurl.persistence.Binding;

public record CreateUrlResult(Binding binding, boolean created) {
}
