import { NgModule } from '@angular/core';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import {
    MdButtonModule,
    MdIconModule,
    MdCheckboxModule,
    MdCardModule,
    MdInputModule,
    MdProgressSpinnerModule,
    MdTooltipModule,
    MdDialogModule,
    MdToolbarModule,
    MdTabsModule,
    MdTableModule,
    MdListModule,
    MdRadioModule,
    MdSelectModule,
    MdSlideToggleModule
} from '@angular/material';

@NgModule({
    exports: [
        BrowserAnimationsModule,
        MdButtonModule,
        MdIconModule,
        MdCheckboxModule,
        MdCardModule,
        MdInputModule,
        MdProgressSpinnerModule,
        MdTooltipModule,
        MdDialogModule,
        MdToolbarModule,
        MdTabsModule,
        MdTableModule,
        MdListModule,
        MdRadioModule,
        MdSelectModule,
        MdSlideToggleModule
    ],
})
export class AppMaterialModule { }
