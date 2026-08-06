package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "global_setting")
public class GlobalSetting extends PanacheEntityBase {

    @Id
    @Column(name = "key")
    public String key;

    @Column(name = "value")
    public String value;
}
