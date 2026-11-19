package com.gnm.model.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Converter
public class IntegerListToArrayConverter implements AttributeConverter<List<Integer>, Integer[]> {

    @Override
    public Integer[] convertToDatabaseColumn(List<Integer> attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.toArray(new Integer[0]);
    }

    @Override
    public List<Integer> convertToEntityAttribute(Integer[] dbData) {
        if (dbData == null) {
            return null;
        }
        return new ArrayList<>(Arrays.asList(dbData));
    }
}

