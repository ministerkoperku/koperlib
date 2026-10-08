package com.koper.koper_lib.kodel.bedrock;

// mixed into EntityRenderState: the bedrock pose rides along with vanilla's own state
public interface BrNosiciel {
    BrKlatka koperlib$br();

    void koperlib$br(BrKlatka k);

    // attachables per slot: 0 main hand, 1 off hand, 2 head, 3 chest, 4 legs, 5 feet
    BrKlatka[] koperlib$att();

    void koperlib$att(BrKlatka[] a);
}
